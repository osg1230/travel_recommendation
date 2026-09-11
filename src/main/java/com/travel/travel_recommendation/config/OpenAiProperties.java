package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel.openai")
public record OpenAiProperties(String apiKey, String model) {
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    // 설정 객체를 실수로 로그에 남겨도 인증키는 출력하지 않는다.
    @Override public String toString() { return "OpenAiProperties[model=" + model + ", configured=" + isConfigured() + "]"; }
}
