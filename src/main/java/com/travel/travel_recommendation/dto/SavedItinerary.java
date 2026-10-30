package com.travel.travel_recommendation.dto;

import java.time.*;
import java.util.List;

/** 내 일정으로 저장한 일정의 조회용 데이터. 저장 당시 초안을 그대로 보여 준다. */
public final class SavedItinerary {
    private SavedItinerary() {}
    public record Summary(Long id, String title, String destination, LocalDate startDate, LocalDate endDate,
                          int dayCount, LocalDateTime savedAt) {}
    public record Item(String type, String typeName, LocalTime start, LocalTime end, String name,
                       String note, String mapUrl, String sourceUrl, Integer travelMinutes, Long placeId) {
        public Item(String type, String typeName, LocalTime start, LocalTime end, String name,
                    String note, String mapUrl, String sourceUrl, Integer travelMinutes) {
            this(type, typeName, start, end, name, note, mapUrl, sourceUrl, travelMinutes, null);
        }
    }
    public record Day(int number, LocalDate date, List<Item> items) {}
    /** editableJobId는 수정안을 만들 수 있는 초안(원래 초안 또는 저장한 일정으로 다시 만든 초안, 만료 전)이다. version은 수정안 적용 시 덮어쓰기 충돌을 막는 값이다. */
    /** 저장 당시 서버가 확인한 여행 조건 결과 한 줄. status는 ItineraryConditionReport.Status 이름이다. */
    public record Check(String name, String status, String label, String detail) {}
    /**
     * checks·prices는 저장(또는 수정안 적용) 당시의 확인 결과다. 예전에 저장한 일정은 비어 있다.
     * pricesCheckedAt은 참고 가격을 조회한 시각(한국 시간 문자열)이며 가격을 찾지 않았으면 null이다.
     */
    public record Detail(Summary summary, String transportName, List<Day> days, java.util.UUID editableJobId, String version,
                         List<Check> checks, List<ItineraryPlan.PriceQuote> prices, String pricesCheckedAt, LastChange lastChange) {
        public Detail(Summary summary, String transportName, List<Day> days) { this(summary, transportName, days, null, null); }
        public Detail(Summary summary, String transportName, List<Day> days, java.util.UUID editableJobId, String version) {
            this(summary, transportName, days, editableJobId, version, List.of(), List.of(), null);
        }
        public Detail(Summary summary, String transportName, List<Day> days, java.util.UUID editableJobId, String version,
                      List<Check> checks, List<ItineraryPlan.PriceQuote> prices, String pricesCheckedAt) {
            this(summary, transportName, days, editableJobId, version, checks, prices, pricesCheckedAt, null);
        }
        /** 바뀐 뒤 일정에서 이 항목 옆에 붙일 표시(새로 넣음·시간 바뀜 등). 없으면 null이다. */
        public String changeMark(int day, int index) {
            return lastChange==null?null:lastChange.comparison().mark(day, index);
        }
        /** 식사·숙소 항목 옆에 보여 줄 저장 당시 참고 가격. */
        public ItineraryPlan.PriceQuote price(Long placeId) {
            return placeId==null?null:prices.stream().filter(q->placeId.equals(q.placeId())).findFirst().orElse(null);
        }
    }
    /**
     * 가장 최근의 수정안 적용이나 되돌리기로 바뀐 내용. 이 일정은 그 직전 한 단계만 남겨 되돌릴 수 있다(SFR-08-04).
     * undone=true면 되돌리기로 바뀐 것이라 한 번 더 누르면 되돌리기를 취소한다. changedAt은 바뀐 시각(한국 시간)이다.
     */
    public record LastChange(boolean undone, String changedAt,
                             com.travel.travel_recommendation.service.ItineraryChanges.Comparison comparison) {}
    /** 수정안을 덮어쓸 수 있는 저장 일정. */
    public record Target(Long id, String title, String version) {}
}
