package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.PriceQuote;
import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.entity.Place;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PlacePriceDetailsTests {
    private final JsonMapper mapper = new JsonMapper();
    private final Instant now = Instant.parse("2026-09-22T00:00:00Z");

    private Place place(String name) {
        return Place.fromKakao(new KakaoPlace("123", "RESTAURANT", name, "강원특별자치도 강릉시", "강원 강릉시 경포로 1", "음식점 > 한식",
            128.9, 37.8, "https://place.map.kakao.com/123", LocalDateTime.of(2026, 9, 21, 0, 0)));
    }
    private PriceQuote quote(String status, Instant checkedAt) {
        boolean found = !"NOT_FOUND".equals(status);
        return new PriceQuote(1L, "동해식당", OpenAiPriceClient.RESTAURANT, status, found ? "1만원" : null, found ? 10000 : null,
            found ? 12000 : null, found ? "대표 메뉴" : null, found ? "https://menu.example.kr/1" : null, checkedAt);
    }

    @Test void foundPriceIsReusedForSevenDaysAndKeepsOtherDetails() {
        var place = place("동해식당");
        place.updateDetails("{\"tourApiOperating\":{\"name\":\"동해식당\"}}");
        var saved = quote("VERIFIED", now.minus(Duration.ofDays(6)));
        place.updateDetails(PlacePriceDetails.write(place, saved, mapper));
        assertThat(place.getDetailsJson()).contains("tourApiOperating", "referencePrice");
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now, mapper)).isEqualTo(saved);
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now.plus(Duration.ofDays(1)), mapper)).isNull();
        // 종류가 다르면(숙소로 찾은 가격 등) 쓰지 않는다.
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.LODGING, now, mapper)).isNull();
    }

    @Test void notFoundIsReusedForADayOnly() {
        var place = place("동해식당");
        place.updateDetails(PlacePriceDetails.write(place, quote("NOT_FOUND", now.minus(Duration.ofHours(23))), mapper));
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now, mapper)).isNotNull();
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now.plus(Duration.ofHours(2)), mapper)).isNull();
    }

    @Test void renamedPlaceOrBrokenDetailsAreNotReused() {
        var place = place("동해식당");
        place.updateDetails(PlacePriceDetails.write(place, quote("VERIFIED", now.minus(Duration.ofHours(1))), mapper));
        var renamed = place("서해식당");
        renamed.updateDetails(place.getDetailsJson());
        assertThat(PlacePriceDetails.read(renamed, OpenAiPriceClient.RESTAURANT, now, mapper)).isNull();
        // 검색 시각이 없는 옛 결과나 앞으로의 시각은 쓰지 않는다.
        place.updateDetails(PlacePriceDetails.write(place, quote("VERIFIED", null), mapper));
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now, mapper)).isNull();
        place.updateDetails(PlacePriceDetails.write(place, quote("VERIFIED", now.plusSeconds(60)), mapper));
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now, mapper)).isNull();
        // 읽을 수 없는 details_json은 덮어쓰지 않는다.
        place.updateDetails("[1,2]");
        assertThat(PlacePriceDetails.write(place, quote("VERIFIED", now), mapper)).isNull();
        place.updateDetails("not json");
        assertThat(PlacePriceDetails.write(place, quote("VERIFIED", now), mapper)).isNull();
        assertThat(PlacePriceDetails.read(place, OpenAiPriceClient.RESTAURANT, now, mapper)).isNull();
    }
}
