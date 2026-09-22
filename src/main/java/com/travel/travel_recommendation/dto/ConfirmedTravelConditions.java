package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 사용자가 확정한 조건. 추천 요청 시 서버가 이 DTO의 스냅샷을 작업 입력으로 저장한다. */
public record ConfirmedTravelConditions(String inputMode, String originalText, String region,
        LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
        Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
        List<TravelKeyword> selectedKeywords, String interpretationSummary) {
    public String keywordNames(String categoryCode) {
        return selectedKeywords.stream().filter(k -> k.categoryCode().equals(categoryCode))
                .map(TravelKeyword::name).collect(java.util.stream.Collectors.joining(", "));
    }
    /** 사용자가 고른 여행 취향 이름 목록(자연·바다 등). 여행지 후보를 고를 때 쓴다. */
    public List<String> themeNames() {
        return selectedKeywords == null ? List.of()
            : selectedKeywords.stream().filter(k -> "THEME".equals(k.categoryCode())).map(TravelKeyword::name).toList();
    }
}
