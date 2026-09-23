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
}
