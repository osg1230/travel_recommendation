package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlacePickServiceTests {
    private static PlaceCandidate place(long id, String subtype) {
        return new PlaceCandidate(id, "장소" + id, "강원 강릉시 경포로 " + id, subtype, "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0));
    }
    // 강릉 1박 2일, 관광지 후보 10곳(1은 해변, 나머지는 문화유적). 관광지 순서를 정하기 전의 작업 입력이다.
    private static Input input(List<Long> previous) {
        var pool = new ArrayList<PlaceCandidate>();
        for (long id = 1; id <= 10; id++) pool.add(place(id, id == 1 ? "여행 > 관광,명소 > 해수욕장,해변" : "여행 > 관광,명소 > 문화유적"));
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15), 2, null, 2, true, false, List.of(), "");
        var view = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(99L, "강릉", "강원", "소개", "https://www.gn.go.kr/", LocalDateTime.of(2026, 9, 1, 0, 0)),
            2, true, pool.subList(0, 6), List.of(place(40, "숙박 > 호텔")), List.of(place(30, "음식점 > 한식")));
        return new Input(view.jobId(), view, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90)
            .withPicking(new Picking(pool, false, previous));
    }

    // AI가 순서 맨 앞에 두고 밤 명소로도 꼽은 해변(1)은 낮 관광지에서 빠지고 저녁 관광 후보 맨 앞에 가며, 그 ID가 작업 입력에 남는다.
    @Test void nightSpotIsKeptForTheEveningAndRemembered() {
        var picks = new OpenAiPlacePickClient.Picks(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), List.of(), List.of(1L));
        var picked = PlacePickService.picked(input(List.of()), picks);
        assertThat(picked.places().attractions()).extracting(PlaceCandidate::placeId).doesNotContain(1L).hasSize(6);
        assertThat(picked.evening()).extracting(PlaceCandidate::placeId).startsWith(1L);
        assertThat(picked.nightPlaceIds()).containsExactly(1L);
        assertThat(picked.pickedByAi()).isTrue();
    }

    // '다른 관광지로 다시 만들기'에서 직전 초안에 있던 밤 명소는 아끼지 않는다(저녁 후보에도 넣지 않는다).
    @Test void lastDraftNightSpotIsNotKept() {
        var picks = new OpenAiPlacePickClient.Picks(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), List.of(), List.of(1L));
        var picked = PlacePickService.picked(input(List.of(1L)), picks);
        assertThat(picked.nightPlaceIds()).isEmpty();
        assertThat(picked.evening()).extracting(PlaceCandidate::placeId).doesNotContain(1L);
    }
}
