package com.travel.travel_recommendation.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * 요청 시 조회한 비행기 안내(SFR-06 교통 확장). 저장하지 않고 조회한 화면에만 보여 준다.
 * serviceDate는 운항편을 조회한 기준일(여행 날짜에 운항편이 있으면 그 날짜, 없으면 오늘), tripDate는 일정의 여행 날짜(미정이면 null)다.
 */
// 리다이렉트 뒤 화면에 한 번 보여 주려고 플래시(세션)에 잠시 담으므로 직렬화할 수 있어야 한다.
public record FlightGuide(String fromAirport, String toAirport, LocalDate serviceDate, LocalDate tripDate, List<Route> routes, String message)
        implements java.io.Serializable {
    public FlightGuide { routes = routes == null ? List.of() : List.copyOf(routes); }
    /** 항공사 하나의 하루 운항 요약. 시각은 "HH:mm"이다. */
    public record Route(String airline, int flights, String firstDeparture, String lastDeparture, int fastestMinutes, Integer minCharge)
            implements java.io.Serializable {}
    /** 여행 날짜가 정해져 있는데 그 날짜의 운항편이 없어 다른 날(오늘) 기준으로 보여 준 경우. */
    public boolean otherDay() { return tripDate != null && !tripDate.equals(serviceDate); }
}
