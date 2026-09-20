package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OperatingHoursValidatorTests {
    private final LocalDate monday = LocalDate.of(2026, 9, 21);
    private PlaceOperatingInfo info(String hours, String closed) { return new PlaceOperatingInfo("123", "12", hours, closed, "", "", Instant.now()); }
    private String check(PlaceOperatingInfo info, LocalDate date, int start, int end) {
        return OperatingHoursValidator.check(info, date, LocalTime.of(start, 0), LocalTime.of(end, 0));
    }
    @Test void validatesBoundariesAndRejectsClosedDayOrOutsideHours() {
        assertThat(check(info("09:00~18:00", "연중무휴"), monday, 9, 18)).contains("일치");
        assertThatThrownBy(() -> check(info("09:00~18:00", "연중무휴"), monday, 8, 10)).isInstanceOf(RecommendationException.class);
        assertThatThrownBy(() -> check(info("09:00~18:00", "연중무휴"), monday, 17, 19)).isInstanceOf(RecommendationException.class);
        assertThatThrownBy(() -> check(info("", "매주 월요일"), monday, 9, 10)).isInstanceOf(RecommendationException.class);
        assertThat(check(info("09:00~18:00", "매주 월요일"), monday.plusDays(1), 9, 10)).contains("일치");
    }
    @Test void doesNotInventMeaningForMissingComplexOrOvernightRules() {
        for (var info : new PlaceOperatingInfo[]{info("09:00~18:00", ""), info("09:00~18:00", "연중무휴(명절 부분 휴관)"),
                info("매표시간 09:00~17:00 관람시간 09:00~18:00", "연중무휴"),
                info("09:00~18:00 / 휴게 12:00~13:00", "연중무휴"), info("22:00~02:00", "연중무휴")}) {
            assertThat(check(info, monday, 9, 10)).contains("확인 필요");
        }
        assertThat(check(null, monday, 9, 10)).contains("확인 필요");
        assertThat(check(info("09:00~18:00", "연중무휴"), null, 9, 10)).contains("확인 필요");
    }
    @Test void staleFutureAndAccommodationInformationCannotConfirmOpening() {
        for (var time : new Instant[]{Instant.now().minus(Duration.ofHours(25)), Instant.now().plusSeconds(600)}) {
            assertThat(check(new PlaceOperatingInfo("1", "12", "09:00~18:00", "연중무휴", "", "", time), monday, 9, 10)).contains("확인 필요");
        }
        assertThat(check(new PlaceOperatingInfo("1", "32", "", "", "15:00", "11:00", Instant.now()), monday, 9, 10)).contains("예약 조건");
    }
}
