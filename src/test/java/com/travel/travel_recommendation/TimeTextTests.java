package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.TimeText;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TimeTextTests {
    private final TimeText time = new TimeText();

    // DB의 UTC 시각(LocalDateTime)과 Instant를 모두 한국 시간(+9시간)으로 보여 준다. 날짜가 바뀌는 경우도 맞춘다.
    @Test void showsUtcValuesInKoreanTime() {
        assertThat(time.dateTime(LocalDateTime.of(2026, 9, 23, 12, 56))).isEqualTo("2026-09-23 21:56");
        assertThat(time.dateTime(Instant.parse("2026-09-23T09:43:00Z"))).isEqualTo("2026-09-23 18:43");
        assertThat(time.date(LocalDateTime.of(2026, 9, 22, 20, 0))).isEqualTo("2026-09-23");
        assertThat(time.seconds(LocalDateTime.of(2026, 1, 1, 0, 0, 5))).isEqualTo("2026-01-01 09:00:05");
        assertThat(time.dateTime(null)).isEmpty();
    }

    // 여행 날짜는 "10월 15일(목)"처럼 월·일·요일로 보여 준다.
    @Test void showsTripDayWithWeekday() {
        assertThat(time.day(LocalDate.of(2026, 10, 15))).isEqualTo("10월 15일(목)");
        assertThat(time.day(LocalDate.of(2026, 10, 18))).isEqualTo("10월 18일(일)");
        assertThat(time.day(null)).isEqualTo("날짜 미정");
    }
}
