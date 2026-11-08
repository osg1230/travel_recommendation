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

    /** 오늘 날짜(한국 시각, yyyy-MM-dd). 날짜 입력의 최소 날짜에 쓴다. 서버 시간대가 UTC여도 한국 0~9시에 하루 이른 날짜가 되지 않는다. */
    public String today() { return LocalDate.now(KOREA).toString(); }

    /** 여행 기간을 "2026.11.05 ~ 2026.11.06 (1박 2일)"처럼 보여 준다. 내 일정 목록과 상세가 같은 표기를 쓰게 한 곳에 둔다. */
    public String period(LocalDate start, LocalDate end) {
        if (start == null || end == null) return "날짜 미정";
        var format = DateTimeFormatter.ofPattern("yyyy.MM.dd");
        long days = java.time.temporal.ChronoUnit.DAYS.between(start, end) + 1;
        return start.format(format) + " ~ " + end.format(format) + " (" + (days == 1 ? "당일" : (days - 1) + "박 " + days + "일") + ")";
    }

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
