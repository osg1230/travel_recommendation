package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 추천 작업 연결 전까지 현재 세션에서만 사용하는 확정 조건이다. */
public record ConfirmedTravelConditions(String inputMode, String originalText, String region,
        LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
        Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
        List<TravelKeyword> selectedKeywords, String interpretationSummary) {
    public String keywordNames(String categoryCode) {
        return selectedKeywords.stream().filter(k -> k.categoryCode().equals(categoryCode))
                .map(TravelKeyword::name).collect(java.util.stream.Collectors.joining(", "));
    }
}
