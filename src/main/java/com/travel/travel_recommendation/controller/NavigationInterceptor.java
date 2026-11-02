package com.travel.travel_recommendation.controller;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 모든 화면의 상단 바가 쓰는 로그인 상태를 요청 속성으로 넣는다. 화면 표시용이며 권한 검사는 서버가 따로 한다.
 * 컨트롤러가 시작되기 전에 넣어 두므로 오류 처리기(@ExceptionHandler)가 그리는 화면에서도 로그인 상태가 그대로 보인다.
 */
public class NavigationInterceptor implements HandlerInterceptor {
    private final boolean signupEnabled;

    public NavigationInterceptor(Environment environment) {
        signupEnabled = environment.acceptsProfiles(Profiles.of("sqlserver", "sqlserver-windows"));
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 익명 사용자도 Authentication 객체를 가질 수 있어 익명 여부까지 확인한다.
        boolean loggedIn = request.getUserPrincipal() instanceof Authentication authentication
            && authentication.isAuthenticated() && !(authentication instanceof AnonymousAuthenticationToken);
        request.setAttribute("loggedIn", loggedIn);
        request.setAttribute("loginId", loggedIn ? request.getUserPrincipal().getName() : "");
        request.setAttribute("admin", loggedIn && request.isUserInRole("ADMIN"));
        request.setAttribute("signupEnabled", signupEnabled);
        return true;
    }
}
