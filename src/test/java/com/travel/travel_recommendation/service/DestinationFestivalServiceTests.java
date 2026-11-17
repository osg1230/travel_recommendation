package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.dto.RegionalFestival;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class DestinationFestivalServiceTests {
    private final FestivalService festivals = mock(FestivalService.class);
    private final DestinationFestivalService service = new DestinationFestivalService(festivals);
    private final DestinationCandidate gangneung = new DestinationCandidate(1L, "강릉", "강원특별자치도 강릉시", "바다", "https://www.gn.go.kr", LocalDateTime.now());
    private final DestinationCandidate busan = new DestinationCandidate(2L, "부산", "부산광역시", "바다", "https://www.visitbusan.net", LocalDateTime.now());

    private static RegionalFestival festival(String id, String title, String start, String end, String address) {
        return new RegionalFestival(id, title, LocalDate.parse(start), LocalDate.parse(end), address, "", "", "");
    }
    private static ConfirmedTravelConditions conditions(LocalDate start, LocalDate end) {
        return new ConfirmedTravelConditions("TEXT", "아무 데나", "", start, end, null, null, null, null, null, List.of(), "");
    }

    @Test void keepsOnlyFestivalsInsideTheDestinationAndTripDates() {
        when(festivals.configured()).thenReturn(true);
        when(festivals.findFestivals(any())).thenAnswer(call -> {
            FestivalService.Province province = call.getArgument(0);
            if (province.regionName().equals("강원")) return List.of(
                festival("1", "강릉커피축제", "2026-10-22", "2026-10-25", "강원특별자치도 강릉시 창해로 14"),
                festival("2", "춘천마임축제", "2026-10-22", "2026-10-25", "강원특별자치도 춘천시 중앙로 1"),
                festival("3", "강릉단풍축제", "2026-11-10", "2026-11-12", "강원특별자치도 강릉시 성산면"));
            return List.of(festival("4", "부산불꽃축제", "2026-11-07", "2026-11-07", "부산광역시 수영구 광안해변로"));
        });
        // 날짜 미정: 여행지 주소의 축제를 모두 쓴다. 시·군 이름이 없는 광역시는 여행지 이름으로 맞춘다.
        var open = service.find(conditions(null, null), List.of(gangneung, busan));
        assertThat(open.get(1L)).extracting(RegionalFestival::title).containsExactly("강릉커피축제", "강릉단풍축제");
        assertThat(open.get(2L)).extracting(RegionalFestival::title).containsExactly("부산불꽃축제");
        // 날짜 확정: 여행 기간과 겹치는 축제만 쓰고, 축제가 없는 여행지는 결과에 넣지 않는다.
        var dated = service.find(conditions(LocalDate.parse("2026-10-24"), LocalDate.parse("2026-10-26")), List.of(gangneung, busan));
        assertThat(dated.get(1L)).extracting(RegionalFestival::title).containsExactly("강릉커피축제");
        assertThat(dated).doesNotContainKey(2L);
    }

    @Test void skipsFestivalsWhenTripIsInAnotherSeasonWithoutDates() {
        when(festivals.configured()).thenReturn(true);
        when(festivals.today()).thenReturn(LocalDate.parse("2026-10-04"));
        when(festivals.findFestivals(any())).thenReturn(List.of(festival("1", "강릉커피축제", "2026-10-22", "2026-10-25", "강원특별자치도 강릉시 창해로 14")));
        // "12월에 가고 싶어"처럼 날짜 없이 다른 계절이면 지금 열리는 축제는 그 여행의 축제가 아니다.
        var winter = new ConfirmedTravelConditions("TEXT", "12월에", "", null, null, null, null, null, null, null,
            List.of(new com.travel.travel_recommendation.dto.TravelKeyword(1L, "SEASON", "겨울", null)), "");
        assertThat(service.find(winter, List.of(gangneung))).isEmpty();
        var autumn = new ConfirmedTravelConditions("TEXT", "아무 데나", "", null, null, null, null, null, null, null,
            List.of(new com.travel.travel_recommendation.dto.TravelKeyword(2L, "SEASON", "가을", null)), "");
        assertThat(service.find(autumn, List.of(gangneung))).containsKey(1L);
    }

    // 축제를 찾는 요청이면 홈 화면 탭 목록(60일·15개) 대신 여행 기간으로 따로 조회한다. 날짜가 미정이면 오늘부터 60일이다(2026-11-17).
    @Test void festivalRequestLooksUpTheTripDatesSeparately() {
        when(festivals.configured()).thenReturn(true);
        when(festivals.today()).thenReturn(LocalDate.parse("2026-11-17"));
        when(festivals.findFestivalsBetween(any(), any(), any())).thenReturn(List.of(
            festival("1", "강릉김밥축제", "2027-02-20", "2027-02-21", "강원특별자치도 강릉시 창해로 14")));
        var festivalSearch = new com.travel.travel_recommendation.dto.PurposeSearch("SKIPPED", List.of(), List.of(), null, null, List.of(), "김밥", true);
        var winterTrip = conditions(LocalDate.parse("2027-02-20"), LocalDate.parse("2027-02-22")).withPurposeSearch(festivalSearch);
        assertThat(service.find(winterTrip, List.of(gangneung)).get(1L)).extracting(RegionalFestival::title).containsExactly("강릉김밥축제");
        verify(festivals).findFestivalsBetween(FestivalService.province("51").orElseThrow(), LocalDate.parse("2027-02-20"), LocalDate.parse("2027-02-22"));
        verify(festivals, never()).findFestivals(any());
        service.during(conditions(null, null), gangneung);
        verify(festivals).findFestivalsBetween(FestivalService.province("51").orElseThrow(), LocalDate.parse("2026-11-17"), LocalDate.parse("2027-01-16"));
    }

    @Test void recommendationContinuesWithoutFestivalsWhenLookupFails() {
        when(festivals.configured()).thenReturn(true);
        when(festivals.findFestivals(any())).thenThrow(new RecommendationException("TOUR_UNAVAILABLE", "조회 실패"));
        assertThat(service.find(conditions(null, null), List.of(gangneung))).isEmpty();
        when(festivals.configured()).thenReturn(false);
        assertThat(service.find(conditions(null, null), List.of(gangneung))).isEmpty();
    }
}
