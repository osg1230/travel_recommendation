package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.config.SecurityConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import jakarta.servlet.http.HttpSession;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@Controller
@Profile({"sqlserver", "sqlserver-windows"})
public class LoginController {
    @GetMapping("/login")
    public String login(@RequestParam(required = false) String next, Authentication authentication, HttpSession session) {
        // 이미 로그인했다면 기존 세션을 유지한 채 시작 화면으로 이동한다.
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:" + SecurityConfig.startPage(authentication);
        }
        // 일정 초안에서 로그인하러 왔다면 돌아갈 주소를 세션에 둔다. 회원가입을 거쳐 로그인해도 세션에 남아 있다.
        if (SecurityConfig.isReturnPath(next)) {
            session.setAttribute(SecurityConfig.LOGIN_RETURN, next);
        }
        return "login";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin/index";
    }
}
