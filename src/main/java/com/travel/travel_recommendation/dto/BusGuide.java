package com.travel.travel_recommendation.dto;

import java.time.*;
import java.util.List;

/**
 * 요청 시 조회한 지역 간 버스 안내(SFR-06-05·06). 출발 위치에서 나온 정보라 저장하지 않고 조회한 화면에만 보여 준다.
 * serviceDate는 조회한 운행 기준일(오늘), tripDate는 일정의 여행 날짜(미정이면 null)다.
 */
// 리다이렉트 뒤 화면에 한 번 보여 주려고 플래시(세션)에 잠시 담으므로 직렬화할 수 있어야 한다.
public record BusGuide(String from, String to, LocalDate serviceDate, LocalDate tripDate, List<Route> routes, String message)
        implements java.io.Serializable {
    public BusGuide { routes = routes == null ? List.of() : List.copyOf(routes); }
    /** 출발 터미널 하나에서 도착 터미널까지의 오늘 직행 배차 요약. */
    public record Route(String kind, String fromTerminal, String toTerminal, int trips, LocalTime firstDeparture,
                        LocalTime lastDeparture, int fastestMinutes, Integer minCharge) implements java.io.Serializable {}
    /** 여행 날짜가 정해져 있고 오늘이 아니면 운행정보가 달라질 수 있다고 따로 안내한다. */
    public boolean futureTrip() { return tripDate != null && !tripDate.equals(serviceDate); }
}
