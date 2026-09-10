package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class LoginUserDetailsService implements UserDetailsService {
    private final UserRepository users;

    @Override
    @Transactional(readOnly = true)
    public UserDetails loadUserByUsername(String loginId) throws UsernameNotFoundException {
        String normalized = loginId == null ? "" : loginId.strip();
        if (!normalized.matches("[a-zA-Z0-9_]{4,50}")) {
            throw new UsernameNotFoundException("Invalid credentials");
        }
        var user = users.findByLoginId(normalized)
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
        // Entity를 세션에 넣지 않는다. 비밀번호 대조와 인증 후 해시 제거는 Security가 맡는다.
        return org.springframework.security.core.userdetails.User.withUsername(user.getLoginId())
                .password(user.getPasswordHash())
                .roles(user.getRole().name())
                .disabled(user.getStatus() != UserStatus.ACTIVE)
                .build();
    }
}
