package com.travel.travel_recommendation.dto;

/** 검증에 사용한 키워드의 ID·이름·값을 함께 보관해 이후 이름 변경에도 입력 내용을 유지한다. */
public record TravelKeyword(Long id, String categoryCode, String name, String valueJson) {
    public String getSelectionValue() { return id == null ? name : id.toString(); }
    public String getName() { return name; }
}
