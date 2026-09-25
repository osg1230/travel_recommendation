package com.travel.travel_recommendation.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ModelAttribute;

/** 모든 화면의 상단 바가 쓰는 로그인 상태를 한곳에서 전달한다. 화면 표시용이며 권한 검사는 서버가 따로 한다. */
@ControllerAdvice
@RequiredArgsConstructor
public class NavigationAdvice {
    private final Environment environment;

    @ModelAttribute("loggedIn")
    boolean loggedIn(Authentication authentication) {
        return authenticated(authentication);
    }

    @ModelAttribute("loginId")
    String loginId(Authentication authentication) {
        return authenticated(authentication) ? authentication.getName() : "";
    }

    @ModelAttribute("admin")
    boolean admin(Authentication authentication) {
        return authenticated(authentication) && authentication.getAuthorities().stream()
            .anyMatch(authority -> authority.getAuthority().equals("ROLE_ADMIN"));
    }

    /**
     * 비회원 결과가 남아 있는 시간(분). 세션이 이 시간 동안 아무 동작이 없으면 끝나고 비회원 추천·초안도 열 수 없다.
     * 오류 처리 화면에는 공통 모델 값이 들어가지 않아 템플릿에서 @navigationAdvice로 직접 부른다.
     */
    public long guestSessionMinutes() {
        return environment.getProperty("server.servlet.session.timeout", java.time.Duration.class,
            java.time.Duration.ofMinutes(30)).toMinutes();
    }

    @ModelAttribute("signupEnabled")
    boolean signupEnabled() {
        return environment.acceptsProfiles(Profiles.of("sqlserver", "sqlserver-windows"));
    }

    // 익명 사용자도 Authentication 객체를 가지므로 익명 여부까지 확인한다.
    private boolean authenticated(Authentication authentication) {
        return authentication != null && authentication.isAuthenticated()
            && !(authentication instanceof org.springframework.security.authentication.AnonymousAuthenticationToken);
    }
}
