package com.travel.travel_recommendation.dto;

import java.time.Instant;

/** 제공자가 응답한 원문이다. 특정 방문 날짜의 영업 여부를 확정한 결과는 아니다. parking은 주차 가능 여부·요금 안내 원문이다. */
public record PlaceOperatingInfo(String contentId, String contentTypeId,
        String hours, String closedDays, String checkIn, String checkOut, String parking, Instant fetchedAt) {
    // 주차 항목을 2026-10-12에 추가했다. 이전 호출과 저장된 JSON(parking 없음)은 빈 값으로 본다.
    public PlaceOperatingInfo(String contentId, String contentTypeId, String hours, String closedDays,
                              String checkIn, String checkOut, Instant fetchedAt) {
        this(contentId, contentTypeId, hours, closedDays, checkIn, checkOut, "", fetchedAt);
    }
}
