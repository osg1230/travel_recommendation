package com.travel.travel_recommendation.dto;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * 관광공사 TourAPI 축제 검색(searchFestival2) 결과 한 건. 지자체가 등록한 정보라 날짜가 바뀔 수 있어 화면에서 확인 안내를 함께 둔다.
 * place는 주소 뒤에 붙는 행사 장소 설명(예: "보은읍 뱃들공원 일원")이고, image는 사진이 없으면 빈 문자열이다.
 * longitude·latitude는 관광공사가 등록한 행사 장소 좌표로, 일정에 넣을 실제 장소를 카카오에서 찾을 때 쓴다. 없으면 null이다.
 */
public record RegionalFestival(String id, String title, LocalDate startDate, LocalDate endDate,
                               String address, String place, String image, String tel, Double longitude, Double latitude) {
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("M.d(E)", Locale.KOREAN);

    public RegionalFestival(String id, String title, LocalDate startDate, LocalDate endDate,
                            String address, String place, String image, String tel) {
        this(id, title, startDate, endDate, address, place, image, tel, null, null);
    }

    public boolean ongoingOn(LocalDate date) { return !startDate.isAfter(date) && !endDate.isBefore(date); }

    /** 화면에 보일 기간. 예) "10.16(금) ~ 10.25(일)", 하루 행사는 "10.9(금)" */
    public String period() {
        return startDate.equals(endDate) ? startDate.format(DAY) : startDate.format(DAY) + " ~ " + endDate.format(DAY);
    }
}
