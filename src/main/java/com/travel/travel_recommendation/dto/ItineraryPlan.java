package com.travel.travel_recommendation.dto;

import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;

/** 작업 입력과 검증된 일정 초안. 장소 정보는 서버 후보 스냅샷을 사용한다. */
public final class ItineraryPlan {
    private ItineraryPlan() {}
    public record Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                        LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                        Constraints constraints) {
        // 처음 만드는 초안과 이전 작업 JSON은 수정 정보·조건 구분 없이 그대로 사용한다.
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, null, null);
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, null);
        }
    }
    /**
     * 필수·선호 조건 구분(SFR-02-07). 필수 장소는 반드시 넣고, 하루 방문 수는 required이면 반드시 지키고
     * 아니면 가능한 경우에만 반영한다. 예산 기준·포함 비용은 가격 자료가 없어 확인 필요 표시에만 쓴다.
     */
    public record Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                              String budgetBasis, List<String> budgetIncludes) {
        public Constraints {
            requiredPlaceIds = requiredPlaceIds == null ? List.of() : List.copyOf(requiredPlaceIds);
            budgetIncludes = budgetIncludes == null ? List.of() : List.copyOf(budgetIncludes);
        }
    }
    public record PlaceRef(Long placeId, String name) {}
    /**
     * 기존 초안을 바탕으로 만든 수정안(SFR-04-07·08-04). 원본 초안은 바꾸지 않는다.
     * kept는 사용자가 유지한 관광지이며 시각 고정이 아니라 장소 유지만 뜻한다.
     */
    public record Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                           String reason, Instant requestedAt) {}
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
