package com.travel.travel_recommendation.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class FestivalPlaceServiceTests {
    @Test void picksVenueNamesFromFestivalPlaceText() {
        // 행정구역과 '일원' 같은 말은 빼고 장소 이름만 검색어로 쓴다.
        assertThat(FestivalPlaceService.venueNames("보은읍 뱃들공원 일원")).containsExactly("뱃들공원");
        assertThat(FestivalPlaceService.venueNames("경포해변 및 경포호수광장")).containsExactly("경포해변", "경포호수광장");
        assertThat(FestivalPlaceService.venueNames("월화거리일원")).containsExactly("월화거리");
        // 검색어는 두 개까지만 쓰고, 장소 이름이 없으면 찾지 않는다.
        assertThat(FestivalPlaceService.venueNames("남대천 둔치, 단오장, 강릉아트센터")).hasSize(2);
        assertThat(FestivalPlaceService.venueNames("강릉시 교동 123")).isEmpty();
        assertThat(FestivalPlaceService.venueNames("")).isEmpty();
        assertThat(FestivalPlaceService.venueNames(null)).isEmpty();
    }

    private static com.travel.travel_recommendation.dto.RegionalFestival festival(String address, String place) {
        var day = java.time.LocalDate.of(2026, 12, 12);
        return new com.travel.travel_recommendation.dto.RegionalFestival("F1", "축제", day, day, address, place, null, null);
    }

    // 일정에 넣는 관광지가 행사장이 아닐 수 있으므로, 행사 장소를 먼저 말하고 관광지는 가장 가까운 곳으로 찾았다고 밝힌다.
    @Test void festivalWhereNamesTheVenueAndSaysWhenThePlaceIsOnlyNearby() {
        // 행사 장소 설명이 없으면 주소를 쓴다. 관광지 이름이 주소에 없으니 가까운 관광지라고 알린다.
        assertThat(ItineraryJobService.festivalWhere(festival("부산광역시 해운대구 수영강변대로 120 (우동)", ""), "주라지 신세계센텀시티점"))
            .isEqualTo("부산광역시 해운대구 수영강변대로 120 (우동)에서 열려요. 행사장에서 가장 가까운 관광지로 주라지 신세계센텀시티점을 찾았어요");
        // 행사 장소 이름으로 찾은 관광지면 덧붙이지 않는다.
        assertThat(ItineraryJobService.festivalWhere(festival("충청북도 보은군 보은읍", "보은읍 뱃들공원 일원"), "뱃들공원"))
            .isEqualTo("보은읍 뱃들공원 일원에서 열려요");
        assertThat(ItineraryJobService.festivalWhere(festival("부산 해운대구 중동", "구남로, 해운대해수욕장 일원"), "해운대 해수욕장"))
            .isEqualTo("구남로, 해운대해수욕장 일원에서 열려요");
        // 행사 장소도 주소도 없으면 관광지 근처라고만 말한다.
        assertThat(ItineraryJobService.festivalWhere(festival("", null), "뱃들공원")).isEqualTo("뱃들공원 근처에서 열려요");
        assertThat(ItineraryJobService.festivalWhere(null, "뱃들공원")).isEqualTo("뱃들공원 근처에서 열려요");
    }
}
