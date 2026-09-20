package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel.tourapi")
public record TourApiProperties(String serviceKey) {
    public boolean isConfigured() { return serviceKey != null && !serviceKey.isBlank(); }

    // 설정 객체를 출력해도 인증키는 남기지 않는다.
    @Override public String toString() {
        return "TourApiProperties[configured=" + isConfigured() + "]";
    }
}
