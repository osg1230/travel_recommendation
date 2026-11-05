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

    // 일정에 3곳을 넣었는데 고른 순서의 앞 3곳(1·2·3) 중 1·3만 들어갔으면 2/3이다.
    @Test void measuresAiTopShare() {
        assertThat(ItineraryQualityReportTests.aiTopShare(List.of(1L, 2L, 3L, 4L, 5L), List.of(1L, 3L, 5L))).isCloseTo(2 / 3.0, within(0.001));
        assertThat(ItineraryQualityReportTests.aiTopShare(List.of(1L, 2L), List.of(2L, 1L))).isEqualTo(1.0);
        assertThat(ItineraryQualityReportTests.aiTopShare(List.of(), List.of())).isEqualTo(0.0);
    }

    // 취향 하나면 맞는 관광지 비율, 둘이면 취향별 수가 고른지 본다(바다 3·문화 1이면 1 - (|0.75-0.5|+|0.25-0.5|)/2 = 0.75).
    @Test void measuresThemeBalance() {
        Map<Long, String> activities = Map.of(1L, "걷기·풍경", 2L, "걷기·풍경", 3L, "걷기·풍경", 4L, "역사·문화", 5L, "체험·놀이");
        var visited = List.of(1L, 2L, 3L, 4L, 5L);
        assertThat(ItineraryQualityReportTests.themeBalance(List.of("바다"), activities, visited)).isCloseTo(0.6, within(0.001));
        assertThat(ItineraryQualityReportTests.themeBalance(List.of("바다", "문화·역사"), activities, visited)).isCloseTo(0.75, within(0.001));
        assertThat(ItineraryQualityReportTests.themeBalance(List.of("바다", "문화·역사"), activities, List.of(1L, 4L))).isEqualTo(1.0);
        assertThat(ItineraryQualityReportTests.themeBalance(List.of(), activities, visited)).isNull();
        assertThat(ItineraryQualityReportTests.themeBalance(List.of("맛집", "휴식"), activities, visited)).isNull();
    }

    // 일정에 들어간 1(80점)·2(50점) 평균 65, 빠진 3(60점)·4(20점) 평균 40. 3은 일정 최저점(50)보다 높아 1곳으로 센다.
    @Test void summarizesValueScoresOfVisitedAndLeftOutPlaces() {
        var values = new LinkedHashMap<Long, com.travel.travel_recommendation.service.PlaceValueScore.Score>();
        for (var e : Map.of(1L, 80.0, 2L, 50.0, 3L, 60.0, 4L, 20.0).entrySet())
            values.put(e.getKey(), new com.travel.travel_recommendation.service.PlaceValueScore.Score(e.getValue(), 0, 0, false, 0, false));
        assertThat(ItineraryQualityReportTests.valueSummary(values, List.of(1L, 2L))).isEqualTo("65 | 40 | 1/2");
    }
}