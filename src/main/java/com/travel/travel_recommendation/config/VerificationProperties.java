package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 확인 검색(반려동물 동반·여행 목적 활동) 실행 방식. auto가 false면 일정 초안 화면의 버튼을 눌렀을 때만 찾고(기본, 비용 절약),
 * true면 초안을 만들 때 자동으로 찾아 AI 준비 안내에도 반영한다(시연용). 환경변수 VERIFY_AUTO로 바꾼다.
 */
@ConfigurationProperties("travel.verification")
public record VerificationProperties(boolean auto) {}
