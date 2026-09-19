package com.travel.travel_recommendation.dto;

import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;

/** 작업 입력과 검증된 일정 초안. 장소 정보는 서버 후보 스냅샷을 사용한다. */
public final class ItineraryPlan {
    private ItineraryPlan() {}
    public record Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                        LocalTime start, LocalTime end, Transport transport, int visitMinutes) {}
    public record Item(String type, Long placeId, String name, LocalTime start, LocalTime end,
                       String note, String mapUrl, String sourceUrl, Integer travelSeconds, Instant checkedAt) {}
    public record Day(int number, LocalDate date, List<Item> items) {}
    public record Result(String destination, List<Day> days, Transport transport, Instant createdAt) {}
    public record View(UUID id, String status, String message, boolean pending, Input input, Result result) {}
}
