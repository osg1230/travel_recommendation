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
}
