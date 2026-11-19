package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.UserRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 회원 본인 탈퇴(SFR-01-05). 계정 행은 운영 기록 참조를 위해 남기고, 상태·탈퇴 시각·익명 닉네임으로 바꾼다.
 * 아이디는 30일 동안 그대로 두어 재사용을 막고, 그 뒤에는 대체 아이디로 바꿔 지운다(anonymizeExpired).
 * 본인이 만든 가상 예약·일정·즐겨찾기·추천 이력·평가·임시 작업은 같은 트랜잭션에서 지운다. 관리자 계정은 탈퇴하지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class WithdrawalService {
    public static final String ANONYMOUS_NICKNAME = "탈퇴한 회원";
    /** 탈퇴한 뒤 아이디를 남겨 두는 기간. */
    static final int LOGIN_ID_KEEP_DAYS = 30;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(WithdrawalService.class);
    private final UserRepository users;
    private final PasswordEncoder encoder;
    private final JdbcTemplate jdbc;
    private final VirtualReservationService reservations;

    /** 탈퇴하면 취소되는 진행 중 예약(결제 대기·예약 확정) 건수. 내 정보 화면의 탈퇴 안내에 보여 준다. */
    @Transactional(readOnly=true, timeout=10)
    public int countActiveReservations(String loginId) {
        Integer count=jdbc.queryForObject("""
            SELECT COUNT(*) FROM dbo.virtual_reservations r JOIN dbo.users u ON u.user_id=r.user_id
            WHERE u.login_id=? AND r.status IN ('PENDING','CONFIRMED')""",Integer.class,loginId);
        return count==null?0:count;
    }

    @Transactional(timeout=15)
    public void withdraw(String loginId, String currentPassword, boolean confirmed) {
        User user=users.findForUpdateByLoginId(loginId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
        if(user.getStatus()!=UserStatus.ACTIVE) throw new AccessDeniedException("Account unavailable");
        if(user.getRole()==UserRole.ADMIN)
            throw new ProfileUpdateException(null,"관리자 계정은 탈퇴할 수 없어요. 다른 관리자에게 계정 정리를 요청해 주세요.");
        if(!confirmed) throw new ProfileUpdateException(null,"탈퇴 안내를 확인하고 동의에 체크해 주세요.");
        if(currentPassword==null || !encoder.matches(currentPassword,user.getPasswordHash()))
            throw new ProfileUpdateException(null,"현재 비밀번호가 올바르지 않아요.");
        Long id=user.getId();
        // 상태를 먼저 WITHDRAWN으로 바꿔 회원 행을 쓰기 잠금으로 잡는다. 지우는 동안 들어온 다른 요청은 이 행을 읽으려다 기다리고,
        // 커밋 뒤에는 탈퇴 회원으로 보고 거절한다(끝에서 바꾸면 그동안 ACTIVE로 보고 새 데이터를 남길 수 있다).
        // 원래 비밀번호로 다시 로그인할 수 없도록 알 수 없는 값으로 바꾼다. 상태로도 로그인은 막힌다.
        user.withdraw(ANONYMOUS_NICKNAME,encoder.encode(UUID.randomUUID().toString()));
        users.flush();
        // 자식 행부터 지운다. 운영 처리 이력(operation_logs)은 관리 기록이라 남긴다.
        // 가상 예약은 점유 수량을 취소 절차로 한 번 복구한 뒤 로그·결제·예약을 지운다. 일정 FK가 있어 일정보다 먼저 처리한다.
        reservations.purgeForWithdrawal(id);
        jdbc.update("DELETE t FROM dbo.place_rating_tags t JOIN dbo.place_ratings r ON r.rating_id=t.rating_id WHERE r.user_id=?",id);
        jdbc.update("DELETE FROM dbo.place_ratings WHERE user_id=?",id);
        jdbc.update("DELETE FROM dbo.favorites WHERE user_id=?",id);
        jdbc.update("DELETE i FROM dbo.itinerary_items i JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id WHERE t.user_id=?",id);
        jdbc.update("DELETE FROM dbo.itineraries WHERE user_id=?",id);
        jdbc.update("DELETE FROM dbo.ai_jobs WHERE user_id=?",id);
        jdbc.update("DELETE c FROM dbo.recommendation_candidates c JOIN dbo.recommendation_requests r ON r.request_id=c.request_id WHERE r.user_id=?",id);
        jdbc.update("DELETE FROM dbo.recommendation_requests WHERE user_id=?",id);
    }

    /** 탈퇴한 지 30일 지난 회원의 아이디를 withdrawn-회원번호로 바꿔 개인 식별정보를 남기지 않는다. 로그에는 건수만 남긴다. */
    @Transactional(timeout=15)
    public int anonymizeExpired() {
        var now=KoreaTime.now();
        int changed=users.replaceWithdrawnLoginIds(UserStatus.WITHDRAWN,now.minusDays(LOGIN_ID_KEEP_DAYS),now);
        if(changed>0) log.info("탈퇴 회원 아이디 {}건을 대체 아이디로 바꿈",changed);
        return changed;
    }
}
