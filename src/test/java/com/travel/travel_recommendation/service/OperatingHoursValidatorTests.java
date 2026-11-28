package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import java.time.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class OperatingHoursValidatorTests {
    private final LocalDate monday = LocalDate.of(2026, 9, 21);
    private PlaceOperatingInfo info(String hours, String closed) { return new PlaceOperatingInfo("123", "12", hours, closed, "", "", Instant.now()); }
    private String check(PlaceOperatingInfo info, LocalDate date, int start, int end) {
        return OperatingHoursValidator.check("오죽헌", info, date, LocalTime.of(start, 0), LocalTime.of(end, 0));
    }
    // 일정 항목 안내문에는 운영시간·휴무와 함께 주차 안내를 적고, 없으면 정보 없음으로 적는다.
    @Test void noteListsParkingAfterHoursAndClosedDays() {
        var withParking = new PlaceOperatingInfo("123", "12", "09:00~18:00", "연중무휴", "", "", "가능", Instant.now());
        assertThat(OperatingHoursValidator.note("오죽헌", withParking, monday, LocalTime.of(10, 0), LocalTime.of(11, 0)))
            .contains("운영시간: 09:00~18:00 / 휴무: 연중무휴").contains("\n주차: 가능");
        assertThat(OperatingHoursValidator.note("오죽헌", info("09:00~18:00", "연중무휴"), monday, LocalTime.of(10, 0), LocalTime.of(11, 0)))
            .contains("\n주차: 정보 없음");
    }
    // 일정 생성이 멈춘 이유를 알 수 있도록 실패 메시지에 장소 이름을 남긴다.
    @Test void conflictMessagesNameThePlaceThatBlocksTheSchedule() {
        assertThatThrownBy(() -> check(info("09:00~18:00", "연중무휴"), monday, 8, 10))
            .isInstanceOf(RecommendationException.class).hasMessageContaining("오죽헌").hasMessageContaining("09:00~18:00");
        assertThatThrownBy(() -> check(info("", "매주 월요일"), monday, 9, 10))
            .isInstanceOf(RecommendationException.class).hasMessageContaining("오죽헌");
        assertThat(OperatingHoursValidator.closedOn(info("09:00~18:00", "매주 월요일"), monday)).isTrue();
        assertThat(OperatingHoursValidator.closedOn(info("09:00~18:00", "매주 월요일"), monday.plusDays(1))).isFalse();
        assertThat(OperatingHoursValidator.closedOn(info("09:00~18:00", "연중무휴"), monday)).isFalse();
        assertThat(OperatingHoursValidator.closedOn(null, monday)).isFalse();
    }
    // 안내문에 '폐업'이 적힌 장소만 폐업으로 본다. 평소 안내에 쓰는 '영업종료'·'폐관'이나 30일이 지난 조회 결과는 폐업으로 보지 않는다.
    @Test void closedDownOnlyWhenTheNoticeSaysSo() {
        var now = Instant.now();
        assertThat(OperatingHoursValidator.closedDown(info("폐업", ""), now)).isTrue();
        assertThat(OperatingHoursValidator.closedDown(info("11:00~21:00", "2026년 3월 폐업"), now)).isTrue();
        assertThat(OperatingHoursValidator.closedDown(info("11:00~21:00 (영업종료 30분 전 주문 마감)", "매주 월요일"), now)).isFalse();
        assertThat(OperatingHoursValidator.closedDown(info("09:00~18:00 (폐관 1시간 전 입장 마감)", "연중무휴"), now)).isFalse();
        assertThat(OperatingHoursValidator.closedDown(null, now)).isFalse();
        var old = new PlaceOperatingInfo("1", "39", "폐업", "", "", "", now.minus(Duration.ofDays(31)));
        assertThat(OperatingHoursValidator.closedDown(old, now)).isFalse();
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
