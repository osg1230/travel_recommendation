package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class CenterPlacesTests {
    private static PlaceCandidate place(long id, String name, double lng, double lat) {
        return new PlaceCandidate(id, name, "주소 " + id, "여행 > 관광,명소", "", "https://place.map.kakao.com/" + id, "https://map",
            LocalDateTime.now(), lng, lat, "KAKAO");
    }

    // 여행지 소개에서 조사를 떼고 일반 단어는 뺀 두 글자 이상 단어만 쓴다.
    @Test void descriptionWordsDropParticlesAndCommonWords() {
        assertThat(CenterPlaces.descriptionWords("한옥마을과 전통 문화, 비빔밥 등 지역 음식을 즐길 수 있는 지역입니다."))
            .containsExactly("한옥마을", "비빔밥");
        assertThat(CenterPlaces.descriptionWords("항구와 섬, 케이블카와 해산물 음식을 즐길 수 있는 지역입니다.")).containsExactly("항구", "케이블카");
        assertThat(CenterPlaces.descriptionWords(null)).isEmpty();
    }

    // GPT가 적은 이름을 먼저, 소개 단어를 다음으로 실제 후보 이름과 맞춘다. 여행지 이름이 앞에 붙어도 맞고, 개수는 limit까지다.
    @Test void picksCandidatesMatchingSuggestedNamesThenDescription() {
        var pool = List.of(place(1, "금성당", 127.15, 35.81), place(2, "전주한옥마을", 127.153, 35.815),
            place(3, "경기전", 127.149, 35.815), place(4, "오목대", 127.155, 35.813));
        String intro = "한옥마을과 전통 문화, 비빔밥 등 지역 음식을 즐길 수 있는 지역입니다.";
        assertThat(CenterPlaces.pick(pool, "전주", intro, List.of("경기전", "없는 장소"), 2)).containsExactly(3L, 2L);
        assertThat(CenterPlaces.pick(pool, "전주", intro, List.of(), 1)).containsExactly(2L);
        assertThat(CenterPlaces.pick(pool, "전주", "바다와 섬을 즐길 수 있는 지역입니다.", List.of(), 2)).isEmpty();
    }

    // 소개 단어가 여러 후보에 맞으면(예: "마을", "서원") 쓰지 않는다. GPT가 적은 이름은 그대로 맞춘다(실제 안동 사례).
    @Test void ambiguousDescriptionWordsAreSkipped() {
        var pool = List.of(place(1, "가송농촌전통테마마을", 128.9, 36.7), place(2, "안동하회마을", 128.5, 36.5),
            place(3, "경광서원", 128.7, 36.6), place(4, "도산서원", 128.8, 36.7));
        String intro = "전통 마을과 서원, 지역 음식을 함께 살펴볼 수 있는 지역입니다.";
        assertThat(CenterPlaces.pick(pool, "안동", intro, List.of(), 2)).isEmpty();
        assertThat(CenterPlaces.pick(pool, "안동", intro, List.of("하회마을", "봉정사", "도산서원"), 2)).containsExactly(2L, 4L);
    }

    // 중심 장소를 맨 앞에 두고, 나머지는 가장 가까운 중심 장소와의 거리 순이다. 중심 장소가 없으면 기존 순서다.
    @Test void aroundCentersPutsCentersFirstThenNearbyPlaces() {
        var center = place(1, "중심", 127.0, 35.0);
        var near = place(2, "가까운 곳", 127.001, 35.0);
        var far = place(3, "먼 곳", 127.5, 35.5);
        var middle = place(4, "가운데", 127.25, 35.25);
        assertThat(TripConstraints.aroundCenters(List.of(far, middle, near, center), List.of(1L), List.of()))
            .containsExactly(center, near, middle, far);
        assertThat(TripConstraints.aroundCenters(List.of(far, center), List.of(), List.of())).hasSize(2);
    }

    // 중심 장소는 후보 안에서 여행 일수만큼만 고를 수 있다.
    @Test void selectRejectsUnknownCentersAndTooManyCenters() {
        var pool = List.of(place(1, "a", 127.0, 35.0), place(2, "b", 127.01, 35.0), place(3, "c", 127.02, 35.0));
        var nine = LocalTime.of(9, 0); var six = LocalTime.of(18, 0);
        assertThatThrownBy(() -> TripConstraints.select(pool, 1, nine, six, 90,
            new Constraints(List.of(), null, false, null, List.of(), null, List.of(99L)))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool, 1, nine, six, 90,
            new Constraints(List.of(), null, false, null, List.of(), null, List.of(1L, 2L))))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class,
                e -> assertThat(e.conflicts().getFirst().reason()).contains("여행 일수(1일)만큼"));
        assertThat(TripConstraints.select(pool, 1, nine, six, 90,
            new Constraints(List.of(), null, false, null, List.of(), null, List.of(2L)))).isNotEmpty();
    }
}
