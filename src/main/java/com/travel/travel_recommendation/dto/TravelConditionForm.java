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
    /** 여행 분위기(여러 개)와 여행 속도(하나). 둘 다 고르지 않아도 된다. */
    private List<String> moods = new ArrayList<>();
    private String pace = "";
    private List<TravelKeyword> selectedKeywords = List.of();

    public String getKeywordNames(String categoryCode) {
        return selectedKeywords.stream().filter(keyword -> keyword.categoryCode().equals(categoryCode))
                .map(TravelKeyword::name).collect(java.util.stream.Collectors.joining(", "));
    }
}
