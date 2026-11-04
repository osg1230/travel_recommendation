package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TagoProperties;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 실제 TAGO 열차정보 호출. 활용신청이 승인된 키가 있을 때만 실행한다. */
@EnabledIfEnvironmentVariable(named = "RUN_TAGO_TESTS", matches = "true")
class TrainLiveTests {
    @Test void readsTodaysTrainsOnTwoRoutes() {
        String key = System.getenv("TAGO_SERVICE_KEY");
        var properties = new TagoProperties(key == null || key.isBlank() ? System.getenv("TOUR_API_SERVICE_KEY") : key);
        assertThat(properties.isConfigured()).as("TAGO 인증키 설정 여부").isTrue();
        var client = new TrainClient(properties, new JsonMapper());
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 2026-11-03·04 확인 기준 KTX가 다니는 구간(서울→부산, 서울→강릉). 오늘 날짜로 조회한다.
        for (String[] route : new String[][] {{"NAT010000", "NAT014445"}, {"NAT010000", "NAT601936"}}) {
            var trains = client.trains(route[0], route[1], today);
            // 자료가 없는 날은 실패가 아니라 건너뛴다.
            assumeTrue(!trains.isEmpty(), "오늘 TAGO에 " + route[0] + "→" + route[1] + " 열차 자료가 없어 검증을 건너뜀");
            assertThat(trains).allSatisfy(train -> {
                assertThat(train.departure().toLocalDate()).isEqualTo(today);
                assertThat(train.minutes()).isBetween(60, 420);
                assertThat(train.grade()).isNotBlank();
            });
            assertThat(TrainGuideService.routes(trains)).isNotEmpty();
        }
    }
}
