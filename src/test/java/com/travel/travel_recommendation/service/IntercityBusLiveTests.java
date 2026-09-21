package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TagoProperties;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 실제 TAGO 시외버스정보 호출. 활용신청이 승인된 키가 있을 때만 실행한다. */
@EnabledIfEnvironmentVariable(named = "RUN_TAGO_TESTS", matches = "true")
class IntercityBusLiveTests {
    @Test void findsServiceTerminalsAndReadsTodaysDirectTrips() {
        String key = System.getenv("TAGO_SERVICE_KEY");
        var properties = new TagoProperties(key == null || key.isBlank() ? System.getenv("TOUR_API_SERVICE_KEY") : key);
        assertThat(properties.isConfigured()).as("TAGO 인증키 설정 여부").isTrue();
        var client = new IntercityBusClient(properties, new JsonMapper());
        // 서비스가 쓰는 터미널 이름은 모두 정확히 하나의 ID로 찾아져야 한다.
        for (var terminal : NearbyRegionService.TERMINALS.values())
            assertThat(client.terminalId(terminal.busName())).as(terminal.busName()).isNotBlank();
        // 2026-09-21 확인 기준 배차가 등록된 구간(속초→강릉). 당일 배차만 있으므로 오늘 날짜로 조회한다.
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        var trips = client.trips(client.terminalId("속초"), client.terminalId("강릉"), today);
        // TAGO는 날짜·구간에 따라 배차 자료가 비기도 한다. 자료가 없는 날은 실패가 아니라 건너뛴다.
        assumeTrue(!trips.isEmpty(), "오늘 TAGO에 속초→강릉 배차 자료가 없어 배차 읽기 검증을 건너뜀");
        assertThat(trips).allSatisfy(trip -> {
            assertThat(trip.departure().toLocalDate()).isEqualTo(today);
            assertThat(trip.minutes()).isBetween(30, 180);
        });
    }
}
