package com.travel.travel_recommendation.dto;

import java.time.*;
import java.util.List;

/** 내 일정으로 저장한 일정의 조회용 데이터. 저장 당시 초안을 그대로 보여 준다. */
public final class SavedItinerary {
    private SavedItinerary() {}
    public record Summary(Long id, String title, String destination, LocalDate startDate, LocalDate endDate,
                          int dayCount, LocalDateTime savedAt) {}
    public record Item(String type, String typeName, LocalTime start, LocalTime end, String name,
                       String note, String mapUrl, String sourceUrl, Integer travelMinutes) {}
    public record Day(int number, LocalDate date, List<Item> items) {}
    public record Detail(Summary summary, String transportName, List<Day> days) {}
}
