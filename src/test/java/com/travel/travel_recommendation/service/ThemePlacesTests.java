package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ThemePlacesTests {
    private static long ids = 1;
    private static PlaceCandidate place(String name, String subtype) {
        return new PlaceCandidate(ids++, name, "주소", subtype, "", "https://place.map.kakao.com/1", "https://map", LocalDateTime.now());
    }

    // 카카오 분류와 시스템 장소 분류를 모두 보고, "산"처럼 짧은 말은 분류 한 단계가 정확히 같을 때만 맞춘다.
    @Test void matchesByCategoryAndNameWithoutLooseShortWords() {
        var themes = List.of("자연", "문화·역사", "휴식", "맛집");
        assertThat(ThemePlaces.matched("경기전", "여행 > 관광,명소 > 문화유적 > 사당,제단", themes)).containsExactly("문화·역사");
        assertThat(ThemePlaces.matched("전주한옥마을", "여행 > 관광,명소", themes)).containsExactly("문화·역사");
        assertThat(ThemePlaces.matched("오죽헌·시립박물관", "박물관·문화유산", themes)).containsExactly("문화·역사");
        assertThat(ThemePlaces.matched("건지산", "여행 > 관광,명소 > 산", themes)).containsExactly("자연");
        assertThat(ThemePlaces.matched("남고산성", "여행 > 관광,명소 > 문화유적 > 산성,성곽", themes)).containsExactly("문화·역사");
        assertThat(ThemePlaces.matched("아중호수", "여행 > 관광,명소 > 호수", themes)).containsExactly("자연", "휴식");
        assertThat(ThemePlaces.matched("삼천1동 막걸리골목", "여행 > 관광,명소 > 테마거리", themes)).isEmpty();
        // 맛집은 관광지 판단에 쓰지 않는다.
        assertThat(ThemePlaces.placeThemes(themes)).containsExactly("자연", "문화·역사", "휴식");
        assertThat(ThemePlaces.placeThemes(List.of("맛집"))).isEmpty();
    }

    // 바다 취향만 해변이 아닌 바다 장소를 더 찾고, 찾은 해안산책로는 바다 취향에 맞는다.
    @Test void seaThemeAddsCoastalTrailSearch() {
        assertThat(ThemePlaces.searchWords(List.of("바다", "맛집"))).containsExactly("해안산책로");
        assertThat(ThemePlaces.searchWords(List.of("자연", "문화·역사"))).isEmpty();
        assertThat(ThemePlaces.searchWords(null)).isEmpty();
        assertThat(ThemePlaces.matched("소돌해안일주 산책로", "여행 > 관광,명소 > 도보여행", List.of("바다"))).containsExactly("바다");
    }

    // 취향마다 돌아가며 한 곳씩 앞에 두고, 맞지 않는 곳은 원래 순서대로 뒤에 둔다.
    @Test void themeFirstInterleavesThemesAndKeepsTheRest() {
        var park1 = place("덕진공원", "여행 > 관광,명소 > 공원");
        var street = place("객사길", "여행 > 관광,명소 > 테마거리");
        var park2 = place("다가공원", "여행 > 관광,명소 > 공원");
        var school = place("전주향교", "여행 > 관광,명소 > 문화유적 > 향교,서당");
        var zoo = place("전주동물원", "여행 > 관광,명소 > 동물원");
        var ordered = List.of(park1, street, park2, school, zoo);
        assertThat(ThemePlaces.themeFirst(ordered, List.of("휴식", "문화·역사")))
            .containsExactly(park1, school, park2, street, zoo);
        // 관광지로 반영할 취향이 없으면 순서를 바꾸지 않는다.
        assertThat(ThemePlaces.themeFirst(ordered, List.of("맛집"))).isSameAs(ordered);
        assertThat(ThemePlaces.themeFirst(ordered, List.of())).isSameAs(ordered);
    }

    // 대표 분류(맨 앞)가 문화·역사인 여행지만 문화·역사를 더하고, 사용자가 고른 취향 뒤에 붙인다.
    @Test void heritageDestinationAddsCultureThemeAfterChosenThemes() {
        assertThat(ThemePlaces.withHeritageDestination(List.of("휴식", "맛집"), "경주", "문화·역사,자연")).containsExactly("휴식", "맛집", "문화·역사");
        assertThat(ThemePlaces.withHeritageDestination(List.of(), "공주", "문화·역사")).containsExactly("문화·역사");
        assertThat(ThemePlaces.withHeritageDestination(List.of("문화·역사"), "전주", "문화·역사,맛집")).containsExactly("문화·역사");
        // 부산("바다,맛집,문화·역사")처럼 대표 분류가 아니거나 분류가 없으면 그대로 둔다.
        assertThat(ThemePlaces.withHeritageDestination(List.of("휴식"), "부산", "바다,맛집,문화·역사")).containsExactly("휴식");
        assertThat(ThemePlaces.withHeritageDestination(List.of("휴식"), "강릉", null)).containsExactly("휴식");
        // 대전은 분류 맨 앞이 문화·역사지만 과학 전시 위주라 더하지 않는다.
        assertThat(ThemePlaces.withHeritageDestination(List.of("휴식"), "대전", "문화·역사,자연,체험·액티비티")).containsExactly("휴식");
    }

    // 원하지 않았으면 사찰·성당은 맨 앞 한 곳만 제자리에 두고 나머지를 뒤로 보낸다.
    @Test void religiousLastKeepsOneAndMovesTheRestBack() {
        var temple1 = place("불국사", "문화,예술 > 종교 > 불교 > 절,사찰");
        var temple2 = place("석굴암", "문화,예술 > 종교 > 불교 > 절,사찰");
        var church = place("성건성당", "문화,예술 > 종교 > 천주교 > 성당");
        var tomb = place("대릉원", "여행 > 관광,명소 > 문화유적 > 릉,묘,총");
        var pond = place("동궁과월지", "여행 > 관광,명소");
        var ordered = List.of(temple1, temple2, tomb, church, pond);
        assertThat(ThemePlaces.religiousLast(ordered, List.of("바다"), "", List.of()))
            .containsExactly(temple1, tomb, pond, temple2, church);
        // 목적 시설·축제 장소로 확인한 곳은 옮기지 않고, 한 곳 남기는 수에도 세지 않는다.
        assertThat(ThemePlaces.religiousLast(ordered, List.of(), null, List.of(church.placeId())))
            .containsExactly(temple1, tomb, church, pond, temple2);
        // 문화·역사 취향이거나 목적에 절·사찰·성당을 적었으면 순서를 바꾸지 않는다.
        assertThat(ThemePlaces.religiousLast(ordered, List.of("문화·역사"), "", List.of())).isSameAs(ordered);
        assertThat(ThemePlaces.religiousLast(ordered, List.of(), "절에 가서 쉬기", List.of())).isSameAs(ordered);
        assertThat(ThemePlaces.religiousLast(ordered, List.of(), "성당 구경", List.of())).isSameAs(ordered);
        // '계절'·'친절'의 '절'은 사찰로 보지 않는다.
        assertThat(ThemePlaces.wantsReligiousSites(List.of(), "계절 꽃 구경, 친절한 숙소")).isFalse();
        assertThat(ThemePlaces.wantsReligiousSites(List.of(), "조용한 절 산책")).isTrue();
    }
}
