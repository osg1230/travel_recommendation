package com.travel.travel_recommendation.controller;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class HomeController {
    private final Environment environment;

    @GetMapping("/")
    public String home(Model model, org.springframework.security.core.Authentication authentication,
                       jakarta.servlet.http.HttpSession session) {
        // 홈의 문장 입력도 조건 입력 화면과 같이 한 번에 분석·추천까지 이어지도록 입력 토큰을 발급한다.
        model.addAttribute("inputToken", TravelConditionController.issueInputToken(session));
        model.addAttribute("projectName", "travel_recommendation");
        model.addAttribute("loggedIn", authentication != null && authentication.isAuthenticated());
        model.addAttribute("loginId", authentication == null ? "" : authentication.getName());
        model.addAttribute("admin", authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        model.addAttribute("signupEnabled", environment.acceptsProfiles(Profiles.of("sqlserver", "sqlserver-windows")));
        return "index";
    }
}
