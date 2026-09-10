package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.config.SecurityConfig;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
@Profile({"sqlserver", "sqlserver-windows"})
public class LoginController {
    @GetMapping("/login")
    public String login(Authentication authentication) {
        // 이미 로그인했다면 기존 세션을 유지한 채 시작 화면으로 이동한다.
        if (authentication != null && authentication.isAuthenticated()) {
            return "redirect:" + SecurityConfig.startPage(authentication);
        }
        return "login";
    }

    @GetMapping("/admin")
    public String admin() {
        return "admin/index";
    }
}
