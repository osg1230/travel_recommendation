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

    @Transactional
    public boolean update(String authenticatedLoginId, @NotNull @Valid ProfileForm form) {
        // 로그인 세션의 아이디만 사용한다. 요청 파라미터로 수정 대상을 받지 않는다.
        User user = users.findForUpdateByLoginId(authenticatedLoginId)
                .orElseThrow(() -> new AccessDeniedException("Account unavailable"));
        checkActive(user);
        if (!encoder.matches(form.getCurrentPassword(), user.getPasswordHash())) {
            throw new ProfileUpdateException("currentPassword", "현재 비밀번호가 올바르지 않습니다.");
        }
        if (users.existsByNicknameAndStatusNotAndIdNot(form.getNickname(), UserStatus.WITHDRAWN, user.getId())) {
            throw new ProfileUpdateException("nickname", "이미 사용 중인 닉네임입니다.");
        }
        boolean nicknameChanged = !user.getNickname().equals(form.getNickname());
        boolean passwordChanged = !form.getNewPassword().isEmpty()
                && !encoder.matches(form.getNewPassword(), user.getPasswordHash());
        if (!nicknameChanged && !passwordChanged) {
            throw new ProfileUpdateException(null, "변경할 내용이 없습니다.");
        }
        // 모든 검증을 마친 뒤 함께 저장한다. 실패하면 트랜잭션이 전체 변경을 취소한다.
        if (nicknameChanged) user.changeNickname(form.getNickname());
        if (passwordChanged) user.changePasswordHash(encoder.encode(form.getNewPassword()));
        users.flush();
        return passwordChanged;
    }

    private void checkActive(User user) {
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new AccessDeniedException("Account unavailable");
        }
    }
}
