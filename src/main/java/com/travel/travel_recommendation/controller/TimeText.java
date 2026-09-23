package com.travel.travel_recommendation.controller;

import java.time.*;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 화면에 보이는 저장·조회 시각을 한국 시간으로 바꾼다. DB와 서버는 시각을 UTC로 다루므로 표시할 때만 바꾼다.
 * 템플릿에서 @timeText.dateTime(값)처럼 부른다. Instant와 UTC 기준 LocalDateTime을 모두 받는다.
 */
@Component("timeText")
public class TimeText {
    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");

    public String dateTime(Object utc) { return format(utc, "yyyy-MM-dd HH:mm"); }
    public String date(Object utc) { return format(utc, "yyyy-MM-dd"); }
    public String seconds(Object utc) { return format(utc, "yyyy-MM-dd HH:mm:ss"); }

    private static String format(Object utc, String pattern) {
        Instant instant = utc instanceof Instant value ? value
            : utc instanceof LocalDateTime value ? value.toInstant(ZoneOffset.UTC) : null;
        return instant == null ? "" : DateTimeFormatter.ofPattern(pattern).withZone(KOREA).format(instant);
    }
}
