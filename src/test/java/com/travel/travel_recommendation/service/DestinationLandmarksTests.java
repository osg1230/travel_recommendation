package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.entity.Place;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class DestinationLandmarksTests {
    private final JsonMapper mapper = new JsonMapper();
    private final Instant now = Instant.parse("2026-10-30T00:00:00Z");
    private final DestinationLandmarks.Lists lists = new DestinationLandmarks.Lists(List.of("경포대", "오죽헌"), List.of("초당순두부"), List.of("세인트존스호텔"));

    private Place destination(String name) {
        return Place.fromKakao(new KakaoPlace("1", "ATTRACTION", name, "강원특별자치도 강릉시", "강원특별자치도 강릉시 경포로 365",
            "여행 > 관광,명소", 128.9, 37.79, "https://place.map.kakao.com/1", LocalDateTime.of(2026, 10, 30, 9, 0)));
    }

    // 연관 관광지까지 받은 목록은 30일 동안 다시 쓰고, 지나거나 여행지 이름이 바뀌면 다시 만들도록 null을 돌려준다. 다른 키는 그대로 둔다.
    @Test void savedNamesAreReusedForThirtyDaysAndKeepOtherDetails() {
        var place = destination("강릉");
        place.updateDetails("{\"tourApiPhoto\":{\"name\":\"강릉\"}}");
        place.updateDetails(DestinationLandmarks.write(place, lists, true, now, mapper));
        assertThat(place.getDetailsJson()).contains("tourApiPhoto", "landmarks");
        assertThat(DestinationLandmarks.read(place, now.plus(Duration.ofDays(29)), mapper)).isEqualTo(lists);
        assertThat(DestinationLandmarks.read(place, now.plus(Duration.ofDays(30)), mapper)).isNull();
        assertThat(DestinationLandmarks.read(place, now.minusSeconds(60), mapper)).isNull();
        var renamed = destination("속초");
        renamed.updateDetails(place.getDetailsJson());
        assertThat(DestinationLandmarks.read(renamed, now, mapper)).isNull();
    }

    // 관광공사 연관 관광지를 받지 못한 목록(GPT 제안만 있음)은 하루 뒤 다시 만든다. 음식점·숙소가 없던 이전 형식도 다시 만든다.
    @Test void listWithoutPopularDataOrOldFormatIsRemade() {
        var place = destination("강릉");
        place.updateDetails(DestinationLandmarks.write(place, lists, false, now, mapper));
        assertThat(DestinationLandmarks.read(place, now.plus(Duration.ofHours(23)), mapper)).isEqualTo(lists);
        assertThat(DestinationLandmarks.read(place, now.plus(Duration.ofDays(1)), mapper)).isNull();
        place.updateDetails("{\"landmarks\":{\"name\":\"강릉\",\"names\":[\"경포대\"],\"popular\":true,\"checkedAt\":\"2026-10-30T00:00:00Z\"}}");
        assertThat(DestinationLandmarks.read(place, now, mapper)).isNull();
    }

    @Test void unreadableDetailsAreNotOverwritten() {
        var place = destination("강릉");
        place.updateDetails("[1,2]");
        assertThat(DestinationLandmarks.write(place, lists, true, now, mapper)).isNull();
        assertThat(DestinationLandmarks.read(place, now, mapper)).isNull();
    }
}
