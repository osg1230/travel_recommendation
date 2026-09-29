package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.validation.annotation.Validated;

@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
@Validated
public class UserService {
    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;

    @Transactional
    public void signup(@NotNull @Valid SignupForm form) {
        if (users.existsByLoginId(form.getLoginId())) {
            throw new DuplicateUserException("loginId", "이미 사용 중인 아이디입니다.");
        }
        if (users.existsByNicknameAndStatusNot(form.getNickname(), UserStatus.WITHDRAWN)) {
            throw new DuplicateUserException("nickname", "이미 사용 중인 닉네임입니다.");
        }
        // 권한과 상태는 입력받지 않는다. User의 기본값 MEMBER / ACTIVE를 사용한다.
        User user = new User(form.getLoginId(), passwordEncoder.encode(form.getPassword()), form.getNickname());
        user.changeHomeArea(form.getHomeArea());
        // 동시에 같은 값으로 가입해도 DB의 UNIQUE 제약이 최종적으로 막는다.
        users.saveAndFlush(user);
    }
}
