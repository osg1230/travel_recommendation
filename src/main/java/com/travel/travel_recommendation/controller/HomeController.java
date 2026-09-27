package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.FestivalService;
import com.travel.travel_recommendation.service.RecommendationException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import lombok.RequiredArgsConstructor;

@Controller
@RequiredArgsConstructor
public class HomeController {
    private final Environment environment;
    private final FestivalService festivalService;

    @GetMapping("/")
    public String home(@RequestParam(required = false) String festivalRegion,
                       @RequestParam(required = false) String festivalPage, Model model,
                       org.springframework.security.core.Authentication authentication,
                       jakarta.servlet.http.HttpSession session) {
        // 홈의 문장 입력도 조건 입력 화면과 같이 한 번에 분석·추천까지 이어지도록 입력 토큰을 발급한다.
        model.addAttribute("inputToken", TravelConditionController.issueInputToken(session));
        model.addAttribute("projectName", "travel_recommendation");
        model.addAttribute("loggedIn", authentication != null && authentication.isAuthenticated());
        model.addAttribute("loginId", authentication == null ? "" : authentication.getName());
        model.addAttribute("admin", authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN")));
        model.addAttribute("signupEnabled", environment.acceptsProfiles(Profiles.of("sqlserver", "sqlserver-windows")));
        addFestivals(festivalRegion, festivalPage, model);
        return "index";
    }

    // 지역 축제: 시·도를 고른 경우에만 관광공사를 조회해 홈 첫 화면이 외부 API 때문에 느려지지 않게 한다.
    private void addFestivals(String festivalRegion, String festivalPage, Model model) {
        model.addAttribute("provinces", FestivalService.PROVINCES);
        model.addAttribute("festivalToday", festivalService.today());
        var province = FestivalService.province(festivalRegion).orElse(null);
        model.addAttribute("festivalProvince", province);
        if (province == null) return;
        if (!festivalService.configured()) {
            model.addAttribute("festivalError", "지금은 축제 정보를 불러올 수 없어요. 관광정보 연결 설정이 필요해요.");
            return;
        }
        try {
            // 쪽 번호가 숫자가 아니면 첫 쪽을 보여 준다. 범위를 벗어난 번호는 서비스가 맞춘다.
            int page = festivalPage != null && festivalPage.matches("[0-9]{1,3}") ? Integer.parseInt(festivalPage) : 1;
            var festivals = festivalService.findFestivalPage(province, page);
            model.addAttribute("festivalPage", festivals);
            model.addAttribute("festivals", festivals.festivals());
        } catch (RecommendationException e) {
            // 축제 조회가 실패해도 홈의 다른 기능은 그대로 쓸 수 있게 안내만 한다.
            model.addAttribute("festivalError", e.getMessage());
        }
    }
}
