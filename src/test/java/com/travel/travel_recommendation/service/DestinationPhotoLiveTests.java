package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TourApiProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * 여행지 대표 경치 사진(중심 장소가 없을 때의 보조 경로)이 실제 관광공사 API에서 동작하는지 본다.
 * 실제 호출이라 RUN_TOUR_PHOTO_LIVE_TESTS=true와 TOUR_API_SERVICE_KEY가 있을 때만 실행한다.
 */
@EnabledIfEnvironmentVariable(named = "RUN_TOUR_PHOTO_LIVE_TESTS", matches = "true")
class DestinationPhotoLiveTests {
    @Test void findsDistrictCodesAndScenicPhotosForGangneungAndJeju() {
        var tour = new TourApiClient(new TourApiProperties(System.getenv("TOUR_API_SERVICE_KEY")), new JsonMapper());
        String gangwon = tour.districtCode(null, "강원특별자치도").orElseThrow();
        String gangneung = tour.districtCode(gangwon, "강릉시").orElseThrow();
        assertThat(gangwon).isEqualTo("51");
        var gangneungPhotos = tour.naturePhotos(gangwon, gangneung);
        System.out.println("강릉 자연 관광지: " + gangneungPhotos.stream().map(p -> p.name() + "[" + p.copyright() + "]").toList());
        assertThat(gangneungPhotos).isNotEmpty();
        assertThat(gangneungPhotos).allSatisfy(p -> assertThat(p.address()).contains("강릉시"));

        // 시·도 하나가 여행지인 제주는 시·군·구 없이 시·도 전체에서 찾는다.
        String jeju = tour.districtCode(null, "제주특별자치도").orElseThrow();
        var jejuPhotos = tour.naturePhotos(jeju, null);
        System.out.println("제주 자연 관광지: " + jejuPhotos.stream().map(TourApiClient.SearchPlace::name).toList());
        assertThat(jejuPhotos).isNotEmpty();
        assertThat(tour.districtCode(gangwon, "없는시")).isEmpty();
    }
}
