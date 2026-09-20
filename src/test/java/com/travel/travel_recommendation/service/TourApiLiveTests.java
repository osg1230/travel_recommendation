package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TourApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named = "RUN_TOUR_API_TESTS", matches = "true")
class TourApiLiveTests {
    @Test void retrievesIntroForExplicitlySelectedTourApiPlace() {
        var properties = new TourApiProperties(System.getenv("TOUR_API_SERVICE_KEY"));
        assertThat(properties.isConfigured()).as("TourAPI 환경변수 설정 여부").isTrue();
        String id = System.getenv("TOUR_API_TEST_CONTENT_ID"), type = System.getenv("TOUR_API_TEST_CONTENT_TYPE");
        assertThat(id).as("TourAPI 표본 contentId").isNotBlank();
        assertThat(type).as("TourAPI 표본 contentTypeId").isNotBlank();
        var client = new TourApiClient(properties, new JsonMapper());
        String name = System.getenv("TOUR_API_TEST_NAME");
        if (name != null && !name.isBlank()) {
            assertThat(client.search(name)).as("검색 결과와 소개정보의 콘텐츠 ID 일치")
                .anyMatch(place -> place.id().equals(id) && place.type().equals(type));
        }
        var result = client.findOperatingInfo(id, type);
        assertThat(result).as("지정한 표본의 소개정보 존재 여부").isPresent();
        assertThat(result.orElseThrow().contentId()).isEqualTo(id);
    }
}
