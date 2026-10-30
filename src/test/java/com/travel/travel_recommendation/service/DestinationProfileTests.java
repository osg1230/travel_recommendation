package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.service.TourApiClient.ClassifiedPlace;
import java.time.*;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class DestinationProfileTests {
    private final JsonMapper mapper = new JsonMapper();
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");

    private Place destination(String name) {
        return Place.fromKakao(new KakaoPlace("1", "ATTRACTION", name, "강원특별자치도 속초시", "강원특별자치도 속초시 설악산로 1",
            "여행 > 관광,명소", 128.5, 38.1, "https://place.map.kakao.com/1", LocalDateTime.of(2026, 10, 30, 9, 0)));
    }

    // 소분류별로 수를 세고 같은 소분류의 같은 이름은 한 번만 센다(일반구마다 조회하면 같은 곳이 겹칠 수 있다). 이름은 5개까지 남긴다.
    @Test void summarizeCountsDistinctNamesPerCategory() {
        var places = new java.util.ArrayList<>(List.of(new ClassifiedPlace("설악산 국립공원(외설악)", "NA040100"),
            new ClassifiedPlace("천불동계곡", "NA010400"), new ClassifiedPlace("천불동계곡", "NA010400")));
        IntStream.rangeClosed(1, 7).forEach(i -> places.add(new ClassifiedPlace("해변" + i, "NA020900")));
        var profile = DestinationProfile.summarize(places);
        assertThat(profile.get("NA010400")).isEqualTo(new DestinationProfile.Category(1, List.of("천불동계곡")));
        assertThat(profile.get("NA020900").count()).isEqualTo(7);
        assertThat(profile.get("NA020900").names()).hasSize(5);
        assertThat(DestinationProfile.count(profile, List.of("NA040100", "NA010400", "NA010100"))).isEqualTo(2);
        assertThat(DestinationProfile.names(profile, List.of("NA040100", "NA010400"), 3)).containsExactly("설악산 국립공원(외설악)", "천불동계곡");
    }

    // 30일 동안 다시 쓰고, 지나거나 여행지 이름이 바뀌면 다시 만들도록 null을 돌려준다. 다른 details_json 키는 그대로 둔다.
    @Test void savedProfileIsReusedForThirtyDaysAndKeepsOtherDetails() {
        var place = destination("속초");
        place.updateDetails("{\"landmarks\":{\"name\":\"속초\"}}");
        var profile = DestinationProfile.summarize(List.of(new ClassifiedPlace("설악산 국립공원(외설악)", "NA040100")));
        place.updateDetails(DestinationProfile.write(place, profile, now, mapper));
        assertThat(place.getDetailsJson()).contains("landmarks", "profile");
        assertThat(DestinationProfile.read(place, now.plus(Duration.ofDays(29)), mapper)).isEqualTo(profile);
        assertThat(DestinationProfile.read(place, now.plus(Duration.ofDays(30)), mapper)).isNull();
        var renamed = destination("양양");
        renamed.updateDetails(place.getDetailsJson());
        assertThat(DestinationProfile.read(renamed, now, mapper)).isNull();
        place.updateDetails("[1]");
        assertThat(DestinationProfile.write(place, profile, now, mapper)).isNull();
    }
}
