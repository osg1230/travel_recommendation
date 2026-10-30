package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DestinationPickServiceTests {
    private static DestinationCandidate destination(long id, String name) {
        return new DestinationCandidate(id, name, "지역 " + name, name + " 소개", "https://example.go.kr/", null);
    }
    private static DestinationProfile.Category category(int count, String... names) { return new DestinationProfile.Category(count, List.of(names)); }
    private final ConfirmedTravelConditions hiking = new ConfirmedTravelConditions("TEXT", "단풍 보러 산에 가고 싶어요", "", null, null, 2, null, 2,
        true, null, List.of(), "", true, "단풍 등산");

    // 원하는 여행의 분류가 있으면 그 분류의 장소 수·대표 이름을 근거로 보내고, 제안 여행지 → 목적 시설이 있는 곳 → 근거 가짓수 순서로 둔다.
    @Test void wishCategoriesBecomeEvidenceAndSuggestedOrFacilityDestinationsComeFirst() {
        var c = hiking.withRegionSuggestion(new RegionSuggestion("FOUND", List.of("NA040100", "NA010100"), List.of(7L), null, Instant.now()))
            .withPurposeSearch(new PurposeSearch("FOUND", List.of("케이블카"), List.of(new PurposeSearch.PurposePlace(100L, 2L, "정읍",
                "내장산 케이블카", "전북 정읍시", "관광지", "https://place.map.kakao.com/1")), null, Instant.now()));
        var profiles = Map.of(
            1L, Map.of("NA010100", category(30, "북한산", "인왕산", "도봉산", "관악산"), "VE070100", category(80, "국립중앙박물관")),
            2L, Map.of("NA040100", category(1, "내장산국립공원"), "NA010100", category(5, "내장산", "입암산")),
            7L, Map.of("NA040100", category(1, "지리산국립공원")));
        var options = DestinationPickService.options(c,
            List.of(destination(1, "서울"), destination(2, "정읍"), destination(7, "구례"), destination(9, "세종")), profiles);

        assertThat(options).extracting(OpenAiDestinationPickClient.Option::id).containsExactly(7L, 2L, 1L, 9L);
        var jeongeup = options.get(1);
        assertThat(jeongeup.evidence()).containsExactly(new OpenAiDestinationPickClient.Evidence("국립공원", 1, List.of("내장산국립공원")),
            new OpenAiDestinationPickClient.Evidence("산·오름·봉우리", 5, List.of("내장산", "입암산")));
        assertThat(jeongeup.facilities()).containsExactly("내장산 케이블카");
        // 원하는 여행과 상관없는 분류(박물관)는 보내지 않고, 대표 이름은 3개까지다.
        assertThat(options.get(2).evidence()).containsExactly(new OpenAiDestinationPickClient.Evidence("산·오름·봉우리", 30, List.of("북한산", "인왕산", "도봉산")));
        assertThat(options.get(3).evidence()).isEmpty();
    }

    // 분류가 없으면(지역 제안을 하지 않은 요청) 여행지마다 장소가 많은 분류 4개를 근거로 보낸다. 근거 분류에 없는 소분류는 뺀다.
    @Test void withoutWishCategoriesTheLargestCategoriesAreSentAndOptionsAreCapped() {
        var profile = Map.of("NA020900", category(12, "경포해변"), "VE010100", category(99, "기타"), "NA010100", category(8, "괘방산"),
            "SH060200", category(5, "중앙시장"), "HS030100", category(3, "보현사"), "VE070100", category(1, "참소리박물관"));
        var options = DestinationPickService.options(hiking, List.of(destination(1, "강릉")), Map.of(1L, profile));
        assertThat(options.getFirst().evidence()).extracting(OpenAiDestinationPickClient.Evidence::category)
            .containsExactly("해변·해수욕장", "산·오름·봉우리", "전통시장", "사찰");

        // 여행지가 많으면 MAX_OPTIONS곳까지만 보내되, 제안 여행지는 등록 순서가 뒤여도 남긴다.
        var many = LongStream.rangeClosed(1, 60).mapToObj(id -> destination(id, "여행지" + id)).toList();
        var suggested = hiking.withRegionSuggestion(new RegionSuggestion("FOUND", List.of("NA010100"), List.of(60L), null, Instant.now()));
        var capped = DestinationPickService.options(suggested, many, Map.of());
        assertThat(capped).hasSize(DestinationPickService.MAX_OPTIONS);
        assertThat(capped.getFirst().id()).isEqualTo(60L);
    }
}
