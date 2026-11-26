package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 행정안전부 인허가 자료(음식점·숙박업 영업 상태) 인증키. 공공데이터포털 계정 키라 TourAPI와 같은 값을 쓸 수 있다. */
@ConfigurationProperties("travel.license")
public record LicenseProperties(String serviceKey) {
    public boolean isConfigured() { return serviceKey != null && !serviceKey.isBlank(); }

    // 설정 객체를 출력해도 인증키는 남기지 않는다.
    @Override public String toString() {
        return "LicenseProperties[configured=" + isConfigured() + "]";
    }
}
