package com.travel.travel_recommendation.config;

import com.travel.travel_recommendation.service.LoginAttemptLimiter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;

// 인증 전에 입력 길이를 제한한다. 비밀번호를 로그나 세션에 기록하지 않는다.
public class LoginInputFilter extends OncePerRequestFilter {
    private final LoginAttemptLimiter limiter;

    public LoginInputFilter(LoginAttemptLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                  FilterChain chain) throws ServletException, IOException {
        if ("POST".equals(request.getMethod()) && "/login".equals(request.getServletPath())) {
            String loginId = request.getParameter("loginId");
            String password = request.getParameter("password");
            if (loginId == null || !loginId.strip().matches("[a-zA-Z0-9_]{4,50}")
                    || password == null || !password.matches("[!-~]{8,64}")) {
                // 다른 로그인 실패와 같이 기록하고 아이디만 다시 채운다.
                SecurityConfig.logDenied(request, "LOGIN_INVALID_INPUT", loginId);
                SecurityConfig.keepLoginId(request, response);
                response.sendRedirect(request.getContextPath() + "/login?error");
                return;
            }
            // 시도가 너무 많으면 비밀번호를 확인하지 않고 돌려보낸다. 맞는 비밀번호여도 같은 화면이라 알아낼 수 있는 것이 없다.
            if (!limiter.tryLogin(loginId, request.getRemoteAddr())) {
                SecurityConfig.logDenied(request, "LOGIN_LIMITED", loginId);
                SecurityConfig.keepLoginId(request, response);
                response.sendRedirect(request.getContextPath() + "/login?limited");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
