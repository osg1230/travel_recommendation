package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.LicenseProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** 실제 행정안전부 인허가 자료 호출(2회). 활용신청이 승인된 키가 있을 때만 실행한다. */
@EnabledIfEnvironmentVariable(named = "RUN_LICENSE_TESTS", matches = "true")
class LicenseLiveTests {
    @Test void findsAnOpenAndAClosedRestaurantInGangneung() {
        String key = System.getenv("LICENSE_SERVICE_KEY");
        var properties = new LicenseProperties(key == null || key.isBlank() ? System.getenv("TOUR_API_SERVICE_KEY") : key);
        assertThat(properties.isConfigured()).as("인허가 자료 인증키 설정 여부").isTrue();
        var client = new LicenseClient(properties, new JsonMapper());
        String region = LicenseClient.regionCode("강원 강릉시 초당순두부길 77").orElseThrow();
        java.util.List<LicenseClient.Business> open, closed;
        // 인허가 서버는 조회가 3초를 넘기는 때가 잦다. 그런 날은 실패가 아니라 건너뛴다.
        try {
            open = client.search("general_restaurants", region, PlaceLicenseService.keyword("초당할머니순두부"));
            closed = client.search("general_restaurants", region, PlaceLicenseService.keyword("파스타예요 강릉초당점"));
        } catch (RecommendationException error) {
            assumeTrue(false, "인허가 자료 조회 실패로 검증을 건너뜀 (" + error.getCode() + ")");
            return;
        }
        // 2026-11-26 확인 기준: 초당할머니순두부는 영업 중, 파스타예요 강릉초당점은 2026-11-03 폐업.
        var openMatches = open.stream().filter(b -> PlaceLicenseService.sameBusiness("초당할머니순두부", "강원 강릉시 초당순두부길 77", b)).toList();
        assertThat(openMatches).isNotEmpty();
        assertThat(PlaceLicenseService.closedDown(openMatches)).isFalse();
        var closedMatches = closed.stream().filter(b -> PlaceLicenseService.sameBusiness("파스타예요 강릉초당점", "강원 강릉시 난설헌로 201", b)).toList();
        assertThat(closedMatches).isNotEmpty();
        assertThat(PlaceLicenseService.closedDown(closedMatches)).isTrue();
    }
}
