package com.travel.travel_recommendation.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** 상단 바가 쓰는 값을 모든 화면에 넣는 NavigationInterceptor를 등록하고, 화면이 직접 부르는 값을 제공한다. */
@ControllerAdvice
@RequiredArgsConstructor
public class NavigationAdvice implements WebMvcConfigurer {
    private final Environment environment;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new NavigationInterceptor(environment)).excludePathPatterns("/css/**", "/js/**", "/images/**");
    }

    /**
     * 비회원 결과가 남아 있는 시간(분). 세션이 이 시간 동안 아무 동작이 없으면 끝나고 비회원 추천·초안도 열 수 없다.
     * 템플릿에서 @navigationAdvice로 직접 부른다.
     */
    public long guestSessionMinutes() {
        return environment.getProperty("server.servlet.session.timeout", java.time.Duration.class,
            java.time.Duration.ofMinutes(30)).toMinutes();
    }
}
