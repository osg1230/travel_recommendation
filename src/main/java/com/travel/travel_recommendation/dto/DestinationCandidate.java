package com.travel.travel_recommendation.dto;
import java.time.LocalDateTime;
/** 공식 출처를 확인한 지역 후보이며 AI 추천 순위나 실시간 운영 정보가 아니다. */
public record DestinationCandidate(Long placeId, String name, String region, String description,
        String sourceUrl, LocalDateTime verifiedAt) {}
