package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("travel.kakao")
public record KakaoMapProperties(String restApiKey) {
    public boolean isConfigured() { return restApiKey != null && !restApiKey.isBlank(); }

    // 설정 확인 로그에 실제 인증키가 노출되지 않도록 한다.
    @Override public String toString() {
        return "KakaoMapProperties[configured=" + isConfigured() + "]";
    }
}
