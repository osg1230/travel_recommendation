package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ProfileForm;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
@Validated
public class ProfileService {
    private final UserRepository users;
    private final PasswordEncoder encoder;

    @Transactional(readOnly = true)
    public String getNickname(String authenticatedLoginId) {
        User user = users.findByLoginId(authenticatedLoginId)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        checkActive(user);
        return user.getNickname();
    }

    /** 내 정보·일정 입력 화면에 채울 '주로 출발하는 곳'. 정하지 않았거나 이용할 수 없는 계정이면 null이다. */
    @Transactional(readOnly = true)
    public String getHomeArea(String authenticatedLoginId) {
        return users.findByLoginId(authenticatedLoginId)
                .filter(user -> user.getStatus() == UserStatus.ACTIVE)
                .map(User::getHomeArea).orElse(null);
    }

    /** 내 정보 저장 결과. 무엇을 바꿨는지 화면에 알려 주는 데 쓴다. */
    public record UpdateResult(boolean nicknameChanged, boolean homeAreaChanged, boolean homeAreaCleared,
                               boolean passwordChanged) {
        /** 예: "닉네임, 주로 출발하는 곳(지움)을 바꿨어요." 비밀번호를 바꾸면 로그인 화면이 따로 알리므로 넣지 않는다. */
        public String message() {
            var items = new java.util.ArrayList<String>();
            if (nicknameChanged) items.add("닉네임");
            if (homeAreaChanged) items.add(homeAreaCleared ? "주로 출발하는 곳(지움)" : "주로 출발하는 곳");
            return items.isEmpty() ? "회원 정보를 저장했어요." : String.join(", ", items) + "을 바꿨어요.";
        }
    }

    @Transactional
    public UpdateResult update(String authenticatedLoginId, @NotNull @Valid ProfileForm form) {
        // 로그인 세션의 아이디만 사용한다. 요청 파라미터로 수정 대상을 받지 않는다.
        User user = users.findForUpdateByLoginId(authenticatedLoginId)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        checkActive(user);
        if (!encoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new ProfileUpdateException("currentPassword", "현재 비밀번호가 올바르지 않아요.");
        }
        // 현재 비밀번호는 바로 위에서 확인했으므로, 같은 값을 새 비밀번호로 적었는지는 글자로 비교한다.
        if (!form.getNewPassword().isEmpty() && form.getNewPassword().equals(form.getCurrentPassword())) {
            throw new ProfileUpdateException("newPassword", "현재 비밀번호와 같아요. 비밀번호를 바꾸지 않으려면 비워 두세요.");
        }
        if (users.existsByNicknameAndStatusNotAndIdNot(form.getNickname(), UserStatus.WITHDRAWN, user.getId())) {
            throw new ProfileUpdateException("nickname", "이미 사용 중인 닉네임입니다.");
        }
        boolean nicknameChanged = !user.getNickname().equals(form.getNickname());
        boolean passwordChanged = !form.getNewPassword().isEmpty();
        String homeArea = form.getHomeArea().isEmpty() ? null : form.getHomeArea();
        boolean homeAreaChanged = !java.util.Objects.equals(user.getHomeArea(), homeArea);
        if (!nicknameChanged && !passwordChanged && !homeAreaChanged) {
            throw new ProfileUpdateException(null, "변경할 내용이 없어요.");
        }
        // 모든 검증을 마친 뒤 함께 저장한다. 실패하면 트랜잭션이 전체 변경을 취소한다.
        if (nicknameChanged) user.changeNickname(form.getNickname());
        if (homeAreaChanged) user.changeHomeArea(homeArea);
        if (passwordChanged) user.changePasswordHash(encoder.encode(form.getNewPassword()));
        users.flush();
        return new UpdateResult(nicknameChanged, homeAreaChanged, homeAreaChanged && homeArea == null, passwordChanged);
    }

    private void checkActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Account unavailable");
        }
    }
}
