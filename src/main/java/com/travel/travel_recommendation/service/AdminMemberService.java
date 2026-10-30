package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.AdminMember;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 관리자의 회원 검색·조회와 상태 변경을 처리한다. 회원 탈퇴·삭제와 장소 데이터 관리는 범위가 아니다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class AdminMemberService {
    private final UserRepository users;
    private final OperationLogRepository logs;
    static final int PAGE_SIZE=20;

    @Transactional(readOnly=true,timeout=10)
    public AdminMember.Page search(String keyword, String status, int page, String adminLoginId) {
        admin(adminLoginId);
        String search=keyword==null || keyword.isBlank()?null:keyword.strip();
        var filter=status(status);
        // LIKE에서 %와 _는 '아무 글자'라는 뜻이라 앞에 \를 붙여 글자 그대로 찾는다. SQL Server는 [도 특수 문자로 본다.
        String pattern=search==null?null:search.replace("\\","\\\\").replace("%","\\%").replace("_","\\_").replace("[","\\[");
        var found=users.searchMembers(pattern,filter,
            PageRequest.of(Math.max(0,page),PAGE_SIZE,Sort.by(Sort.Direction.DESC,"id")));
        return new AdminMember.Page(found.getContent().stream().map(this::summary).toList(),
            found.getNumber(),found.getTotalPages(),found.getTotalElements(),
            search==null?"":search,filter==null?"":filter.name());
    }

    @Transactional(readOnly=true,timeout=10)
    public AdminMember.Detail detail(Long userId, String adminLoginId) {
        admin(adminLoginId);
        var member=users.findById(userId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"회원을 찾을 수 없어요."));
        return new AdminMember.Detail(summary(member),member.getUpdatedAt(),changeable(member)==null,
            changeable(member),history(member.getId()));
    }

    /** 정상·이용 제한만 바꾸고 사유와 함께 운영 이력에 남긴다. 상태 변경과 기록은 같은 트랜잭션이다. */
    @Transactional(timeout=10)
    public void changeStatus(Long userId, String status, String reason, String adminLoginId) {
        var admin=admin(adminLoginId);
        // 브라우저는 줄바꿈을 \r\n(2자)으로 보내지만 화면의 maxlength는 1자로 센다. 같은 기준으로 세도록 \n으로 바꾼다.
        String trimmed=reason==null?"":reason.replace("\r\n","\n").strip();
        if(trimmed.isEmpty() || trimmed.length()>1000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"변경 사유를 1자 이상 1000자 이하로 입력해 주세요.");
        var target=status(status);
        if(target==null || target==UserStatus.WITHDRAWN)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"바꿀 수 있는 상태는 정상과 이용 제한이에요.");
        // 회원 행을 잠그고 읽는다. 같은 회원의 탈퇴·정보 수정과 겹치면 한쪽이 끝난 뒤 최신 상태로 처리한다.
        var member=users.findForUpdateById(userId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"회원을 찾을 수 없어요."));
        String blocked=changeable(member);
        if(blocked!=null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,blocked);
        var before=member.getStatus();
        if(before==target) throw new ResponseStatusException(HttpStatus.CONFLICT,"이미 같은 상태예요.");
        member.changeStatus(target);
        logs.save(OperationLog.memberStatusChanged(admin.getId(),member.getId(),before,target,trimmed));
        users.flush();
    }

    /** 상태를 바꿀 수 없으면 이유를, 바꿀 수 있으면 null을 돌려준다. */
    private String changeable(User member) {
        if(member.getRole()==UserRole.ADMIN) return "관리자 계정의 상태는 이 화면에서 바꿀 수 없어요.";
        if(member.getStatus()==UserStatus.WITHDRAWN) return "탈퇴한 회원의 상태는 바꿀 수 없어요.";
        return null;
    }
    private List<AdminMember.StatusChange> history(Long userId) {
        var found=logs.findTop10ByTargetTypeAndTargetIdOrderByIdDesc("USER",userId);
        var actors=new HashMap<Long,String>();
        // 기록에는 관리자 식별자만 남으므로 표시할 때만 아이디를 찾는다.
        for(var log:found) if(log.getActorUserId()!=null)
            actors.computeIfAbsent(log.getActorUserId(),id->users.findById(id).map(User::getLoginId).orElse("(삭제된 계정)"));
        return found.stream().map(log->new AdminMember.StatusChange(log.getCreatedAt(),
            statusName(log.getBeforeStatus()),statusName(log.getAfterStatus()),log.getReason(),
            log.getActorUserId()==null?"시스템":actors.get(log.getActorUserId()))).toList();
    }
    private User admin(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId)
            .filter(u->u.getStatus()==UserStatus.ACTIVE && u.getRole()==UserRole.ADMIN)
            .orElseThrow(()->new AccessDeniedException("Admin required"));
    }
    private UserStatus status(String value) {
        if(value==null || value.isBlank()) return null;
        try { return UserStatus.valueOf(value.strip()); }
        catch(IllegalArgumentException error) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"알 수 없는 회원 상태예요.");
        }
    }
    private AdminMember.Summary summary(User member) {
        return new AdminMember.Summary(member.getId(),member.getLoginId(),member.getNickname(),
            member.getRole()==UserRole.ADMIN?"관리자":"회원",member.getStatus().name(),
            statusName(member.getStatus().name()),member.getCreatedAt());
    }
    private String statusName(String status) {
        if(status==null) return "";
        return switch(status) {
            case "ACTIVE" -> "정상";
            case "RESTRICTED" -> "이용 제한";
            case "WITHDRAWN" -> "탈퇴";
            default -> status;
        };
    }
}
