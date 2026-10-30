package com.travel.travel_recommendation.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RegionSuggestionServiceTests {
    private static DestinationProfile.Category category(int count, String... names) { return new DestinationProfile.Category(count, List.of(names)); }

    @Test void sameRegionComparesProvinceAliasesAndCity() {
        assertThat(RegionSuggestionService.sameRegion("강원도 강릉시", "강원특별자치도 강릉시")).isTrue();
        assertThat(RegionSuggestionService.sameRegion("부산광역시", "부산")).isTrue();
        assertThat(RegionSuggestionService.sameRegion("전라남도 여수시", "전남광주통합특별시 여수시")).isTrue();
        assertThat(RegionSuggestionService.sameRegion("강원특별자치도 고성군", "경상남도 고성군")).isFalse();
        assertThat(RegionSuggestionService.sameRegion("경상남도 산청군", "경상남도 함양군")).isFalse();
        assertThat(RegionSuggestionService.sameRegion("부산광역시", "부산광역시 해운대구")).isFalse();
    }

    // 기존 여행지처럼 시·군 글자를 떼되, 같은 이름의 여행지가 이미 있으면 시·군 이름을 그대로 쓴다.
    @Test void nameDropsCitySuffixUnlessItCollides() {
        assertThat(RegionSuggestionService.name("전남광주통합특별시 구례군", Set.of("여수", "강릉"))).isEqualTo("구례");
        assertThat(RegionSuggestionService.name("경상남도 고성군", Set.of("고성"))).isEqualTo("고성군");
        assertThat(RegionSuggestionService.name("경상북도 영주시", Set.of())).isEqualTo("영주");
    }

    // 분류는 추가 근거가 된 원하는 여행의 분류(등록 장소가 있는 것)를 여행 취향으로 바꾼 것이다.
    @Test void subtypeComesFromTheWishedCategoriesThatHaveRegisteredPlaces() {
        Map<String, DestinationProfile.Category> profile = Map.of(
            "NA010100", category(8, "노고단", "반야봉"), "NA040100", category(1, "지리산국립공원"),
            "HS030100", category(4, "화엄사", "천은사"), "NA020500", category(2, "어느섬"), "EX050100", category(2, "지리산온천"));
        assertThat(RegionSuggestionService.subtype(profile, List.of("NA010100", "NA040100", "NA010400"))).isEqualTo("자연");
        assertThat(RegionSuggestionService.subtype(profile, List.of("NA020500", "HS030100", "EX050100", "NA010100"))).isEqualTo("바다,문화·역사,휴식");
        assertThat(RegionSuggestionService.subtype(profile, List.of("NA010400"))).isNull();
        assertThat(RegionSuggestionService.theme("VE020400")).isEqualTo("체험·액티비티");
        assertThat(RegionSuggestionService.theme("VE040300")).isEqualTo("자연");
    }

    // 소개는 근거 분류마다 대표 장소 이름을 하나씩 돌아가며 고른다(산 이름만 나오지 않고 국립공원도 나오게).
    @Test void descriptionTakesNamesFromEachCategoryInTurn() {
        Map<String, DestinationProfile.Category> profile = Map.of(
            "NA010100", category(8, "노고단", "반야봉", "만복대"), "NA040100", category(1, "지리산국립공원"));
        assertThat(RegionSuggestionService.description(profile, List.of("NA010100", "NA040100", "NA010400")))
            .isEqualTo("노고단·지리산국립공원·반야봉 등 산·오름·봉우리, 국립공원을 둘러볼 수 있는 지역입니다.");
    }
}
