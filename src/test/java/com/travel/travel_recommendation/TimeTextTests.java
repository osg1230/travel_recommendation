package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.TimeText;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TimeTextTests {
    private final TimeText time = new TimeText();

    // DB의 시각(LocalDateTime)은 한국 시각 그대로 보여 주고, Instant는 한국 시간대로 바꿔 보여 준다(2026-10-10, SQL 017).
    @Test void showsStoredValuesAsKoreanTime() {
        assertThat(time.dateTime(LocalDateTime.of(2026, 9, 23, 21, 56))).isEqualTo("2026-09-23 21:56");
        assertThat(time.dateTime(Instant.parse("2026-09-23T09:43:00Z"))).isEqualTo("2026-09-23 18:43");
        assertThat(time.date(LocalDateTime.of(2026, 9, 23, 5, 0))).isEqualTo("2026-09-23");
        assertThat(time.seconds(LocalDateTime.of(2026, 1, 1, 9, 0, 5))).isEqualTo("2026-01-01 09:00:05");
        assertThat(time.dateTime(null)).isEmpty();
    }

    // 한국 자정 전후에 날짜가 밀리거나 이미 변환된 저장값에 9시간을 다시 더하지 않는지 확인한다.
    @Test void keepsKoreanDatesAcrossMidnightAndYearEnd() {
        assertThat(time.seconds(Instant.parse("2026-12-31T14:59:59Z"))).isEqualTo("2026-12-31 23:59:59");
        assertThat(time.seconds(Instant.parse("2026-12-31T15:00:00Z"))).isEqualTo("2027-01-01 00:00:00");
        assertThat(time.seconds(LocalDateTime.of(2027, 1, 1, 0, 0))).isEqualTo("2027-01-01 00:00:00");
    }

    // 여행 날짜는 "10월 15일(목)"처럼 월·일·요일로 보여 준다.
    @Test void showsTripDayWithWeekday() {
        assertThat(time.day(LocalDate.of(2026, 10, 15))).isEqualTo("10월 15일(목)");
        assertThat(time.day(LocalDate.of(2026, 10, 18))).isEqualTo("10월 18일(일)");
        assertThat(time.day(null)).isEqualTo("날짜 미정");
    }
}
