package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** 음식 축제 확인(2026-11-16, 음식·축제 목적 계획 3단계). 외부 조회는 DestinationFestivalService를 흉내 낸다. */
class PurposePlaceServiceTests {
    private final DestinationFestivalService festivals = mock(DestinationFestivalService.class);
    private final PurposePlaceService service = new PurposePlaceService(null, null, null, null, null, null, null, null, festivals);
    private static final DestinationCandidate GIMCHEON = new DestinationCandidate(11029L, "김천", "경상북도 김천시", "김밥 음식점이 있는 지역", "https://korean.visitkorea.or.kr/", LocalDateTime.of(2026, 11, 16, 0, 0));
    private static final DestinationCandidate GANGNEUNG = new DestinationCandidate(1L, "강릉", "강원특별자치도 강릉시", "", "https://www.gn.go.kr/", LocalDateTime.of(2026, 9, 1, 0, 0));

    private static PurposePlaceService.Task task(LocalDate start, LocalDate end, List<Long> suggested) {
        var c = new ConfirmedTravelConditions("TEXT", "김밥 축제 ㄱㄱ", "", start, end, start == null ? null : 2, null, null, null, null, List.of(), "")
            .withPurpose("김밥 축제 관람").withRegionSuggestion(new RegionSuggestion("FOUND", List.of("EX010100"), suggested, null, Instant.now()));
        return new PurposePlaceService.Task(UUID.randomUUID(), "{}", c, List.of(GANGNEUNG, GIMCHEON));
    }
    private static PurposeSearch search(boolean festival) {
        return new PurposeSearch("SKIPPED", List.of(), List.of(), "", Instant.now(), List.of("맛집")).withFood("김밥", festival);
    }

    // 여행 기간에 제안 여행지에서 그 음식 축제가 열리면 목적을 그대로 두고 축제를 알린다.
    @Test void festivalInTheTripKeepsThePurpose() {
        when(festivals.configured()).thenReturn(true);
        var t = task(LocalDate.of(2026, 10, 24), LocalDate.of(2026, 10, 25), List.of(11029L));
        when(festivals.during(any(), any())).thenReturn(List.of(new RegionalFestival("1", "2026 김천김밥축제", LocalDate.of(2026, 10, 24),
            LocalDate.of(2026, 10, 25), "경상북도 김천시 대항면", "직지문화공원", "", "")));
        var check = service.foodFestival(t, search(true));
        assertThat(check.purpose()).isNull();
        assertThat(check.message()).contains("2026 김천김밥축제").contains("를 관광공사 행사 정보에서 찾았어요");
        verify(festivals).during(t.conditions(), GIMCHEON);
        verify(festivals, never()).during(t.conditions(), GANGNEUNG);
    }

    // 축제를 찾지 못하면 목적을 '김밥 맛집 탐방'으로 바꾸고 이유를 알린다. 제목에 음식이 없는 행사는 그 음식 축제로 보지 않는다.
    @Test void noFestivalSwitchesToAFoodTour() {
        when(festivals.configured()).thenReturn(true);
        when(festivals.during(any(), any())).thenReturn(List.of(new RegionalFestival("2", "김천 국화축제", LocalDate.of(2026, 11, 20),
            LocalDate.of(2026, 11, 22), "경상북도 김천시", "", "", "")));
        var check = service.foodFestival(task(null, null, List.of(11029L)), search(true));
        assertThat(check.purpose()).isEqualTo("김밥 맛집 탐방");
        assertThat(check.message()).isEqualTo("앞으로 두 달 안에 열리는 김밥 축제를 관광공사 행사 정보에서 찾지 못해 '김밥 맛집 탐방'으로 바꿔 추천해요.");
        var dated = service.foodFestival(task(LocalDate.of(2026, 12, 12), LocalDate.of(2026, 12, 13), List.of(11029L)), search(true));
        assertThat(dated.message()).startsWith("여행 기간에 열리는 김밥 축제를");
    }

    // 지역을 확인하지 못했거나 행사 정보를 조회할 수 없어도 맛집 탐방으로 바꾼다. 음식만 말했으면(축제 아님) 확인하지 않는다.
    @Test void unknownRegionOrNoTourApiAlsoSwitchesAndFoodOnlyIsLeftAlone() {
        when(festivals.configured()).thenReturn(true);
        assertThat(service.foodFestival(task(null, null, List.of()), search(true)).message()).startsWith("김밥 축제가 열리는 지역을 확인하지 못해");
        when(festivals.configured()).thenReturn(false);
        assertThat(service.foodFestival(task(null, null, List.of(11029L)), search(true)).purpose()).isEqualTo("김밥 맛집 탐방");
        assertThat(service.foodFestival(task(null, null, List.of(11029L)), search(false))).isNull();
    }
}
