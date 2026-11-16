package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MoodPlacesTests {
    private static PlaceCandidate place(String name, String subtype) {
        return new PlaceCandidate(1L, name, "강원 강릉시 경포로 1", subtype, "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // 분류·장소 이름의 낱말로 고른 분위기와 어울리는지 본다. 여러 분위기를 골랐으면 처음 맞는 분위기를 돌려준다.
    @Test void matchesPlacesByWordsInTheirKindOrName() {
        var market = place("강릉중앙시장", "여행 > 관광,명소 > 전통시장");
        var arboretum = place("솔향수목원", "여행 > 관광,명소 > 수목원");
        var gallery = place("하슬라아트월드", "문화시설 > 미술관");
        var museum = place("오죽헌시립박물관", "문화시설 > 박물관");
        assertThat(MoodPlaces.matched(market, List.of("활기찬"))).isEqualTo("활기찬");
        assertThat(MoodPlaces.matched(arboretum, List.of("한적한"))).isEqualTo("한적한");
        assertThat(MoodPlaces.matched(gallery, List.of("한적한", "감성적인"))).isEqualTo("감성적인");
        assertThat(MoodPlaces.matched(museum, List.of("한적한", "활기찬", "감성적인"))).isNull();
        assertThat(MoodPlaces.matched(market, List.of())).isNull();
    }

    // 고른 분위기는 MOOD 키워드 이름이다. 다른 조건이나 조건이 없으면 비어 있다.
    @Test void chosenMoodsComeFromMoodKeywords() {
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", null, null, 2, null, null, true, null,
            List.of(new TravelKeyword(1L, "THEME", "바다", null), new TravelKeyword(2L, "MOOD", "한적한", null),
                new TravelKeyword(3L, "MOOD", "감성적인", null)), "");
        assertThat(MoodPlaces.chosen(conditions)).containsExactly("한적한", "감성적인");
        assertThat(MoodPlaces.chosen(null)).isEmpty();
    }
}
