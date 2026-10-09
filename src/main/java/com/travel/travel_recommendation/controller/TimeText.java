package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.KoreaTime;
import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 화면에 보이는 저장·조회 시각을 한국 시간 문자열로 만든다. DB의 시각은 한국 시각(KoreaTime)으로 저장되어 있고, Instant는 한국 시간대로 바꿔 보여 준다.
 * 템플릿에서 @timeText.dateTime(값)처럼 부른다. Instant와 한국 시각 LocalDateTime을 모두 받는다.
 */
@Component("timeText")
public class TimeText {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public String dateTime(Object utc) { return format(utc, "yyyy-MM-dd HH:mm"); }
    public String date(Object utc) { return format(utc, "yyyy-MM-dd"); }
    public String seconds(Object utc) { return format(utc, "yyyy-MM-dd HH:mm:ss"); }

    /** 여행 날짜(시간대 없는 날짜)를 "10월 15일(목)"처럼 보여 준다. 일정의 일차 제목에 쓴다. 날짜가 없으면 "날짜 미정"이다. */
    public String day(LocalDate date) {
        if (date == null) return "날짜 미정";
        return date.getMonthValue() + "월 " + date.getDayOfMonth() + "일(" + "월화수목금토일".charAt(date.getDayOfWeek().getValue() - 1) + ")";
    }

    private static String format(Object utc, String pattern) {
        Instant instant = utc instanceof Instant value ? value
            : utc instanceof LocalDateTime value ? value.atZone(KoreaTime.ZONE).toInstant() : null;
        return instant == null ? "" : DateTimeFormatter.ofPattern(pattern).withZone(KOREA).format(instant);
    }
}
