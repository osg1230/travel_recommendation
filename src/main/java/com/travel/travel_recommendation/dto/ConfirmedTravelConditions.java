package com.travel.travel_recommendation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** 사용자가 확정한 조건. 추천 요청 시 서버가 이 DTO의 스냅샷을 작업 입력으로 저장한다. */
public record ConfirmedTravelConditions(String inputMode, String originalText, String region,
        LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
        Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
        List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose,
        PurposeSearch purposeSearch, boolean purposeSuggested, List<String> excludedRegions, RegionSuggestion regionSuggestion) {
    // excludedRegions는 "부산 말고"처럼 사용자가 가지 않겠다고 한 지역 이름이다. 추천 후보에서 뺀다. 이전 JSON에는 없다.
    // regionSuggestion은 지역을 정하지 않은 요청에서 AI가 제안하고 서버가 확인한 여행지다. 추천 작업이 후보를 고르기 전에 채운다.
    public ConfirmedTravelConditions {
        excludedRegions = excludedRegions == null ? List.of() : List.copyOf(excludedRegions);
    }
    public ConfirmedTravelConditions(String inputMode, String originalText, String region,
            LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
            Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
            List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose,
            PurposeSearch purposeSearch, boolean purposeSuggested, List<String> excludedRegions) {
        this(inputMode, originalText, region, startDate, endDate, durationDays, budget, partySize, accommodationNeeded, includeNearby,
            selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, excludedRegions, null);
    }
    public ConfirmedTravelConditions(String inputMode, String originalText, String region,
            LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
            Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
            List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose,
            PurposeSearch purposeSearch, boolean purposeSuggested) {
        this(inputMode, originalText, region, startDate, endDate, durationDays, budget, partySize,
            accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, null);
    }
    // purposeSuggested가 true면 travelPurpose는 사용자가 말한 목적이 아니라, 목적을 말하지 않아 AI가 계절에 맞춰 제안한 여행 방향이다.
    public ConfirmedTravelConditions(String inputMode, String originalText, String region,
            LocalDate startDate, LocalDate endDate, Integer durationDays, BigDecimal budget,
            Integer partySize, Boolean accommodationNeeded, Boolean includeNearby,
            List<TravelKeyword> selectedKeywords, String interpretationSummary, boolean directRecommendation, String travelPurpose,
            PurposeSearch purposeSearch) {
        this(inputMode, originalText, region, startDate, endDate, durationDays, budget, partySize,
            accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, false);
    }
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
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, true, travelPurpose, purposeSearch, purposeSuggested, excludedRegions, regionSuggestion);
    }
    public ConfirmedTravelConditions withSchedule(Integer days, Boolean stay) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, days,
            budget, partySize, stay, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, excludedRegions, regionSuggestion);
    }
    /** 출발일이 미정이던 조건에 일정 생성 때 고른 출발일을 넣는다. 마지막 날은 여행 일수로 계산한다. */
    public ConfirmedTravelConditions withStartDate(LocalDate start) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, start, start.plusDays(durationDays - 1), durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, excludedRegions, regionSuggestion);
    }
    public ConfirmedTravelConditions withPurpose(String purpose) {
        return withPurpose(purpose, false);
    }
    /** suggested가 true면 AI가 제안한 여행 방향으로 표시한다. */
    public ConfirmedTravelConditions withPurpose(String purpose, boolean suggested) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, purpose, purposeSearch, suggested, excludedRegions, regionSuggestion);
    }
    /** 화면과 안내 문구에서 목적을 부르는 말. 사용자가 말한 목적과 AI 제안을 구분한다. */
    public String purposeLabel() {
        return purposeSuggested ? "AI가 제안한 여행 방향" : "여행 목적";
    }
    public ConfirmedTravelConditions withExcludedRegions(List<String> regions) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, regions, regionSuggestion);
    }
    public ConfirmedTravelConditions withPurposeSearch(PurposeSearch search) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, search, purposeSuggested, excludedRegions, regionSuggestion);
    }
    /** 목적 시설이 있는 여행지 ID. 목적 검색을 하지 않았거나 찾지 못했으면 비어 있다. */
    public List<String> purposeThemes() {
        return purposeSearch == null ? List.of() : purposeSearch.themes();
    }
    public List<Long> purposeDestinationIds() {
        return purposeSearch == null ? List.of() : purposeSearch.destinationIds();
    }
    public ConfirmedTravelConditions withRegionSuggestion(RegionSuggestion suggestion) {
        return new ConfirmedTravelConditions(inputMode, originalText, region, startDate, endDate, durationDays,
            budget, partySize, accommodationNeeded, includeNearby, selectedKeywords, interpretationSummary, directRecommendation, travelPurpose, purposeSearch, purposeSuggested, excludedRegions, suggestion);
    }
    /** AI가 제안하고 서버가 근거를 확인한 여행지 ID. 지역 제안을 하지 않았으면 비어 있다. */
    public List<Long> suggestedDestinationIds() {
        return regionSuggestion == null ? List.of() : regionSuggestion.destinationIds();
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
