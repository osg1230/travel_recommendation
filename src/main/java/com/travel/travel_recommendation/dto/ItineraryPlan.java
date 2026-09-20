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
    /** AI가 작성한 보조 안내. 서버가 만든 시각·장소·순서를 바꾸지 않으며 검증 완료를 뜻하지 않는다. */
    public record Assist(String summary, List<DayAdvice> days, Instant createdAt) {}
    public record DayAdvice(int number, String advice) {}
    public record Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist) {
        // 보조 안내를 붙이기 전의 초안과 이전 작업 JSON은 assist 없이 그대로 사용한다.
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt) {
            this(destination, days, transport, createdAt, null);
        }
        public Result withAssist(Assist assist) { return new Result(destination, days, transport, createdAt, assist); }
    }
    public record View(UUID id, String status, String message, boolean pending, Input input, Result result) {}
}
