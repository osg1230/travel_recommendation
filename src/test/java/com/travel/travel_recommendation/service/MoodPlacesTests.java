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
        // 해수욕장·해변은 어느 분위기에도 넣지 않는다(두 분위기에 모두 걸려 차이가 없어졌다).
        assertThat(MoodPlaces.matched(place("경포해수욕장", "여행 > 관광,명소 > 해수욕장,해변"), List.of("활기찬", "감성적인"))).isNull();
    }

    // 장소 목록을 모을 때 고른 분위기마다 검색어 두 개를 더한다. 고르지 않았으면 더 찾지 않는다.
    @Test void searchWordsFollowTheChosenMoods() {
        assertThat(MoodPlaces.searchWords(List.of("한적한"))).containsExactly("수목원", "둘레길");
        assertThat(MoodPlaces.searchWords(List.of("감성적인", "활기찬"))).containsExactly("카페거리", "전망대", "테마파크", "유원지");
        assertThat(MoodPlaces.searchWords(List.of())).isEmpty();
    }

    // 후보 앞에 둘 곳은 분위기 검색으로 찾는 종류만이다. 시장처럼 어울리기만 하는 종류는 아니다.
    @Test void searchedKindsAreOnlyWhatTheMoodSearchFinds() {
        assertThat(MoodPlaces.searchedKind("강릉카페거리", "여행 > 관광,명소 > 테마거리 > 카페거리", List.of("감성적인"))).isTrue();
        assertThat(MoodPlaces.searchedKind("해파랑길 38코스(바우길06구간)", "여행 > 관광,명소 > 도보여행 > 해파랑길", List.of("한적한"))).isTrue();
        assertThat(MoodPlaces.searchedKind("강릉중앙시장", "여행 > 관광,명소 > 전통시장", List.of("활기찬"))).isFalse();
        assertThat(MoodPlaces.searchedKind("하슬라아트월드", "여행 > 관광,명소 > 테마파크", List.of())).isFalse();
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
