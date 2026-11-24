package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TrainGuideServiceTests {
    private final ItineraryJobService itineraries = mock(ItineraryJobService.class);
    private final TrainClient trains = mock(TrainClient.class);
    private final KakaoMapClient maps = mock(KakaoMapClient.class);
    private final TrainGuideService service = new TrainGuideService(itineraries, trains, maps);
    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
    private final KakaoMapClient.Location jung = new KakaoMapClient.Location("서울특별시 중구", 126.975, 37.556, Instant.now());
    private final KakaoMapClient.Location gyeongpo = new KakaoMapClient.Location("강원특별자치도 강릉시 경포", 128.90, 37.79, Instant.now());

    private TrainClient.Train train(LocalDate day, String grade, int hour, int minute, int minutes, Integer charge) {
        var departure = day.atTime(hour, minute);
        return new TrainClient.Train(departure, departure.plusMinutes(minutes), grade, "1", charge);
    }
    private static KakaoMapClient.TravelTime minutes(int minutes) {
        return new KakaoMapClient.TravelTime(Transport.PUBLIC_TRANSIT, minutes * 60, 1000, Instant.now());
    }

    // 서울 중구에서 가장 가까운 역은 서울역, 경포에서는 강릉역이다.
    @Test void nearestStationsByStraightLine() {
        assertThat(TrainStations.nearest(jung, 2).stream().map(TrainStations.Station::name)).containsExactly("서울", "용산");
        assertThat(TrainStations.nearest(gyeongpo, 1).getFirst().id()).isEqualTo("NAT601936");
        // 역 코드는 서로 겹치지 않는다.
        assertThat(TrainStations.ALL.stream().map(TrainStations.Station::id).distinct().count()).isEqualTo(TrainStations.ALL.size());
    }

    // 편도 시간 = 집→역 + 승차 대기 15분 + 가장 짧은 열차 + 역→관광지. 여행 날짜 시간표가 있으면 그 날짜를 쓴다.
    @Test void sumsStationLegsWaitingAndShortestTrainOnTripDate() {
        LocalDate trip = today.plusDays(5);
        when(trains.configured()).thenReturn(true);
        when(trains.trains("NAT010000", "NAT601936", trip)).thenReturn(List.of(train(trip, "KTX-이음", 5, 6, 117, 27600), train(trip, "KTX-이음", 8, 0, 114, 27600)));
        when(maps.travelTime(eq(jung), any(), eq(Transport.PUBLIC_TRANSIT))).thenReturn(minutes(12));
        when(maps.travelTime(any(), eq(gyeongpo), eq(Transport.PUBLIC_TRANSIT))).thenReturn(minutes(25));
        var leg = service.fastest(jung, gyeongpo, trip).orElseThrow();
        assertThat(leg.minutes()).isEqualTo(12 + 15 + 114 + 25);
        assertThat(leg.basis()).contains("서울역 → 강릉역").contains("1시간 54분").contains("여행 날짜 운행 기준");
        verify(trains, never()).trains(anyString(), anyString(), eq(today));
    }

    // 여행 날짜 시간표가 비어 있으면 오늘 시간표를 쓰고 그 사실을 적는다. 열차가 하나도 없으면 비어 있다.
    @Test void fallsBackToTodayAndStopsOnAuthFailure() {
        LocalDate trip = today.plusDays(60);
        when(trains.configured()).thenReturn(true);
        when(trains.trains(anyString(), anyString(), any())).thenReturn(List.of());
        when(trains.trains("NAT010000", "NAT601936", today)).thenReturn(List.of(train(today, "KTX-이음", 6, 0, 120, null)));
        when(maps.travelTime(any(), any(), eq(Transport.PUBLIC_TRANSIT))).thenReturn(minutes(10));
        var leg = service.fastest(jung, gyeongpo, trip).orElseThrow();
        assertThat(leg.minutes()).isEqualTo(10 + 15 + 120 + 10);
        assertThat(leg.basis()).contains("오늘 운행 기준");

        when(trains.trains(anyString(), anyString(), any())).thenReturn(List.of());
        assertThat(service.fastest(jung, gyeongpo, null)).isEmpty();
        // 활용신청이 안 된 키면 다른 역 조합을 더 묻지 않는다.
        clearInvocations(trains);
        when(trains.trains(anyString(), anyString(), any())).thenThrow(new RecommendationException("TRAIN_AUTH", "인증"));
        assertThat(service.fastest(jung, gyeongpo, null)).isEmpty();
        verify(trains, times(1)).trains(anyString(), anyString(), any());
        // 키가 없으면 묻지 않는다.
        when(trains.configured()).thenReturn(false);
        assertThat(service.fastest(jung, gyeongpo, null)).isEmpty();
    }

    // 안내는 열차 종류별로 묶어 횟수·첫차·막차·가장 짧은 소요·최저 어른 요금을 내고, 빠른 종류부터 보여 준다.
    @Test void groupsTrainsByGrade() {
        var routes = TrainGuideService.routes(List.of(train(today, "무궁화호", 6, 10, 330, 28600), train(today, "KTX", 5, 13, 157, 59800),
            train(today, "KTX", 22, 0, 160, 59800), train(today, "무궁화호", 20, 5, 340, null)));
        assertThat(routes).extracting(TrainGuide.Route::grade).containsExactly("KTX", "무궁화호");
        assertThat(routes.getFirst()).satisfies(r -> {
            assertThat(r.trains()).isEqualTo(2);
            assertThat(r.firstDeparture()).isEqualTo("05:13");
            assertThat(r.lastDeparture()).isEqualTo("22:00");
            assertThat(r.fastestMinutes()).isEqualTo(157);
            assertThat(r.minCharge()).isEqualTo(59800);
        });
        assertThat(routes.get(1).minCharge()).isEqualTo(28600);
    }

    private View view(LocalDate start, List<String> nearby) {
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", start, start, 1, null, 1, false, true, List.of(), "");
        var pool = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(1L, "강릉", "강원특별자치도 강릉시", "소개", "https://visitgangneung.net/", LocalDateTime.of(2026, 9, 1, 0, 0)),
            1, false, List.of(), List.of(), List.of());
        var input = new Input(pool.jobId(), pool, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), Transport.PUBLIC_TRANSIT, 90);
        var regions = nearby.stream().map(n -> new NearbyRegion(2L, n, "지역", "소개", "https://tour.example.kr/", 40, "경로", null, List.of())).toList();
        var result = new Result("강릉", List.of(), Transport.PUBLIC_TRANSIT, Instant.now())
            .withNearby(new Nearby("RECOMMENDED", "이유", "INTERCITY_BUS", regions, null, Instant.now()));
        return new View(UUID.randomUUID(), "COMPLETED", "", false, input, result);
    }

    // 적은 출발지에서 가까운 역과 주변 지역(울산) 터미널에서 가까운 역 사이 열차를 요약한다. 목록에 없는 도착지는 받지 않는다.
    @Test void guideSummarizesTrainsToNearbyRegion() {
        UUID job = UUID.randomUUID();
        when(itineraries.view(eq(job), any(), any())).thenReturn(view(today.plusDays(3), List.of("울산")));
        when(maps.findOrigin("서울역")).thenReturn(new KakaoMapClient.Origin("서울특별시 중구", "서울", jung));
        when(trains.trains(anyString(), anyString(), any())).thenReturn(List.of());
        when(trains.trains(eq("NAT010000"), eq("NAT750726"), eq(today))).thenReturn(List.of(train(today, "KTX-이음", 7, 0, 150, 50000)));
        var guide = service.guide(job, "user", null, "울산", "서울역", null, null);
        assertThat(guide.fromStation()).isEqualTo("서울역");
        assertThat(guide.toStation()).isEqualTo("태화강역");
        assertThat(guide.routes()).singleElement().satisfies(r -> assertThat(r.fastestMinutes()).isEqualTo(150));
        // 여행 날짜 시간표가 없어 오늘 기준으로 보여 준 것을 화면에 알린다.
        assertThat(guide.otherDay()).isTrue();

        assertThatThrownBy(() -> service.guide(job, "user", null, "부산", "서울역", null, null))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_INPUT"));
        // 여행지로 가는데 출발지가 없으면 먼저 출발지를 물어본다.
        assertThatThrownBy(() -> service.guide(job, "user", null, "강릉", "", null, null))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_ORIGIN"));
        // 출발지를 적어도 여행지 관광지 좌표가 없는 일정이면 도착역을 정하지 못한다고 알린다.
        assertThatThrownBy(() -> service.guide(job, "user", null, "강릉", "서울역", null, null))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_NO_TARGET"));
    }
}
