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
    public String home(Model model) {
        model.addAttribute("projectName", "travel_recommendation");
        model.addAttribute("signupEnabled", environment.acceptsProfiles(Profiles.of("sqlserver", "sqlserver-windows")));
        return "index";
    }
}
