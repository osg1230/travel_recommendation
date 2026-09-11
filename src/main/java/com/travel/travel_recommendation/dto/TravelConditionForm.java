package com.travel.travel_recommendation.dto;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.Setter;

/** 여행 조건 입력 화면의 값. 확인 전에는 날짜와 금액도 문자열로 받아 오류를 안내한다. */
@Getter
@Setter
public class TravelConditionForm {
    private final String draftId = java.util.UUID.randomUUID().toString();
    private String inputMode = "KEYWORD";
    private String originalText = "";
    private String region = "";
    private String startDate = "";
    private String endDate = "";
    private String durationDays = "";
    private String season = "";
    private String companion = "";
    private List<String> themes = new ArrayList<>();
    private String budget = "";
    private String transportation = "";
    private List<TravelKeyword> selectedKeywords = List.of();

    public String getKeywordNames(String categoryCode) {
        return selectedKeywords.stream().filter(keyword -> keyword.categoryCode().equals(categoryCode))
                .map(TravelKeyword::name).collect(java.util.stream.Collectors.joining(", "));
    }
}
