package com.travel.travel_recommendation.dto;

import java.time.Instant;

/** 제공자가 응답한 원문이다. 특정 방문 날짜의 영업 여부를 확정한 결과는 아니다. */
public record PlaceOperatingInfo(String contentId, String contentTypeId,
        String hours, String closedDays, String checkIn, String checkOut, Instant fetchedAt) {}
