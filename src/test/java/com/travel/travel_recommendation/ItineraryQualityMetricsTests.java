package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.KakaoMapClient;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 품질 측정 보고서(ItineraryQualityReportTests)의 배치 지표 계산만 외부 API 없이 확인한다. */
class ItineraryQualityMetricsTests {
    private static Item item(String type, long id, String name, int fromHour, int fromMinute, int minutes, double longitude) {
        var start = LocalTime.of(fromHour, fromMinute);
        return new Item(type, id, name, start, start.plusMinutes(minutes), "", "https://map.kakao.com/link/map/" + name + ",37.7," + longitude, null, null, null);
    }

    // A(128.800) → C(128.820) → 식당(128.805) → B(128.810): A·B·C를 가장 짧게 도는 순서(A → B → C)보다 1.5배 길고,
    // 식당은 C와 B 사이가 아니라 B 너머에 있어 B 쪽으로 더 갔다 와야 한다(약 0.88km). 14시에 관광을 마쳐 18시까지 240분이 남는다.
    @Test void measuresRouteMealDetourIdleTimeAndVisitMinutes() {
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 1), 1, null, 2, false, false, List.of(), "");
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(99L, "강릉", "강원", "소개", "https://www.gn.go.kr/", LocalDateTime.of(2026, 9, 1, 0, 0)),
            1, false, List.of(), List.of(), List.of());
        var input = new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 60);
        var items = List.of(
            item("VISIT", 1, "A", 9, 0, 60, 128.800), item("TRANSIT", 3, "A → C", 10, 0, 20, 128.820),
            item("VISIT", 3, "C", 10, 20, 60, 128.820), item("TRANSIT", 9, "C → 식당", 11, 20, 20, 128.805),
            item("MEAL", 9, "식당", 11, 40, 60, 128.805), item("TRANSIT", 2, "식당 → B", 12, 40, 20, 128.810),
            item("VISIT", 2, "B", 13, 0, 60, 128.810));
        var result = new Result("강릉", List.of(new Day(1, LocalDate.of(2026, 10, 1), items)), KakaoMapClient.Transport.CAR, Instant.now());

        var measured = ItineraryQualityReportTests.arrangement(input, result);

        assertThat(measured.minVisit()).isEqualTo(60);
        assertThat(measured.maxVisit()).isEqualTo(60);
        assertThat(measured.routeRatios()).singleElement().satisfies(r -> assertThat(r).isCloseTo(1.5, within(0.01)));
        assertThat(measured.mealDetours()).singleElement().satisfies(d -> assertThat(d).isCloseTo(0.88, within(0.01)));
        assertThat(measured.idleMinutes()).containsExactly(240);
        assertThat(measured.days()).singleElement().asString().startsWith("1일차: A → C → B");
    }
}
