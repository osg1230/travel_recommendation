package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TripConstraintsTests {
    private static final LocalTime NINE = LocalTime.of(9, 0), SIX = LocalTime.of(18, 0);
    private static List<PlaceCandidate> pool(int size) {
        return LongStream.rangeClosed(1, size).mapToObj(id -> new PlaceCandidate(id, "장소" + id, "강원 강릉시 경포로 " + id,
            "관광", "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0))).toList();
    }
    private static List<Long> ids(List<PlaceCandidate> places) { return places.stream().map(PlaceCandidate::placeId).toList(); }
    private static Constraints c(List<Long> required, Integer daily, boolean dailyRequired) {
        return new Constraints(required, daily, dailyRequired, null, List.of());
    }

    @Test void withoutConstraintsKeepsPreviousRule() {
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, null))).containsExactly(1L, 2L, 3L, 4L);
        assertThat(ids(TripConstraints.select(pool(16), 7, NINE, SIX, 90, null))).hasSize(8);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(), null, false)))).containsExactly(1L, 2L, 3L, 4L);
    }

    @Test void requiredPlacesComeFirstAndDailyCountSetsTotal() {
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(9L, 12L), null, false)))).containsExactly(9L, 12L, 1L, 2L);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(9L), 3, true)))).containsExactly(9L, 1L, 2L, 3L, 4L, 5L);
        assertThat(ids(TripConstraints.select(pool(16), 3, NINE, SIX, 90, c(List.of(), 1, true)))).hasSize(3);
    }

    @Test void preferredDailyCountIsReducedToWhatFitsInsteadOfFailing() {
        // 09:00~13:00, 체류 90분이면 하루 약 1곳이다. 선호 조건은 줄여서 반영한다.
        assertThat(TripConstraints.estimatedDailyCapacity(NINE, LocalTime.of(13, 0), 90)).isEqualTo(1);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, LocalTime.of(13, 0), 90, c(List.of(), 4, false)))).hasSize(2);
        // 후보가 적으면 있는 만큼만 쓴다.
        assertThat(ids(TripConstraints.select(pool(3), 2, NINE, SIX, 60, c(List.of(), 3, false)))).hasSize(3);
    }

    @Test void contradictoryRequiredConditionsAreReportedNotRelaxed() {
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(1L, 2L, 3L), 1, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e -> {
                assertThat(e.conflicts()).singleElement().satisfies(conflict -> {
                    assertThat(conflict.reason()).contains("필수 방문 장소 3곳을 하루 1곳씩 2일");
                    assertThat(conflict.choices()).contains("하루 방문 수를 '가능하면 반영'으로 바꾸기");
                });
            });
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 1, NINE, LocalTime.of(13, 0), 90, c(List.of(), 3, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("하루 약 1곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(3), 2, NINE, SIX, 60, c(List.of(), 2, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("후보가 3곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 3, NINE, SIX, 60, c(List.of(), 3, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("전체 최대 8곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 1, NINE, SIX, 90, c(LongStream.rangeClosed(1, 9).boxed().toList(), null, false)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("최대 8곳")));
    }

    @Test void rejectsForgedPlacesAndValues() {
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(99L), null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(1L, 1L), null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(), 5, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, new Constraints(List.of(), null, false, "HALF", List.of()))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, new Constraints(List.of(), null, false, "TOTAL", List.of("TAX")))).isInstanceOf(IllegalArgumentException.class);
    }
}
