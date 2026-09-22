package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 사용자가 확정한 조건. 추천 요청 시 서버가 이 DTO의 스냅샷을 작업 입력으로 저장한다. */
public record ConfirmedTravelConditions(String inputMode, String originalText, String region,
        LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
        Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
        List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose,
        PurposeSearch purposeSearch) {
    // 기존 저장 JSON과 단계별 입력 흐름도 계속 읽을 수 있게 한다. purposeSearch는 추천 작업이 목적 시설을 찾은 뒤에 채운다.
    public ConfirmedTravelConditions(String inputMode, String originalText, String region,
            LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
            Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
            List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose) {
        this(inputMode, originalText, region, startDate, endDate, durationDays, budget, partySize,
            accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, null);
    }
    public ConfirmedTravelConditions(String inputMode, String originalText, String region,
            LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
            Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
            List<TravelKeyword> selectedKeywords, String interpretationSummary) {
        this(inputMode, originalText, region, startDate, endDate, durationDays, budget, partySize,
            accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, false, "");
    }
    public ConfirmedTravelConditions forDirectRecommendation() {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, true, travelPurpose, purposeSearch);
    }
    public ConfirmedTravelConditions withSchedule(Integer days, Boolean stay) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, days,
            budget, partySize, stay, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch);
    }
    public ConfirmedTravelConditions withPurpose(String purpose) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, purpose, purposeSearch);
    }
    public ConfirmedTravelConditions withPurposeSearch(PurposeSearch search) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, search);
    }
    /** 목적 시설이 있는 여행지 ID. 목적 검색을 하지 않았거나 찾지 못했으면 비어 있다. */
    public List<Long> purposeDestinationIds() {
        return purposeSearch == null ? List.of() : purposeSearch.destinationIds();
    }
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
