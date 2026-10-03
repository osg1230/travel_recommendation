package com.travel.travel_recommendation.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FlightGuideServiceTests {
    private final ItineraryJobService itineraries = mock(ItineraryJobService.class);
    private final FlightClient flights = mock(FlightClient.class);
    private final KakaoMapClient maps = mock(KakaoMapClient.class);
    private final FlightGuideService service = new FlightGuideService(itineraries, flights, maps);
    private final KakaoMapClient.Location songpa = new KakaoMapClient.Location("서울특별시 송파구", 127.1, 37.5, Instant.now());
    private final KakaoMapClient.Location seongsan = new KakaoMapClient.Location("제주 서귀포시 성산읍", 126.94, 33.46, Instant.now());

    private static FlightClient.Flight flight(String airline, int depHour, int depMinute, int minutes, Integer charge) {
        var departure = LocalDateTime.of(2026, 10, 3, depHour, depMinute);
        return new FlightClient.Flight(departure, departure.plusMinutes(minutes), airline, "X1", charge);
    }

    // 송파구에서 가장 가까운 공항은 김포, 성산일출봉에서는 제주다.
    @Test void nearestAirportsByStraightLine() {
        assertThat(Airports.nearest(songpa, 3).stream().map(Airports.Airport::name)).containsExactly("김포국제공항", "인천국제공항", "원주공항");
        assertThat(Airports.nearest(seongsan, 1).getFirst().id()).isEqualTo("NAARKPC");
    }

    // 편도 시간 = 집→공항 + 출발 대기 60분 + 가장 짧은 비행 + 도착 대기 20분 + 공항→관광지. 운항편이 없는 공항 조합은 건너뛴다.
    @Test void sumsGroundLegsWaitingAndShortestFlight() {
        when(flights.configured()).thenReturn(true);
        when(flights.flights(eq("NAARKSS"), eq("NAARKPC"), any())).thenReturn(List.of(flight("아시아나 항공", 6, 30, 75, 61900), flight("대한항공", 7, 0, 65, 70000)));
        when(maps.travelTime(eq(songpa), any(), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 40 * 60, 20000, Instant.now()));
        when(maps.travelTime(any(), eq(seongsan), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 50 * 60, 45000, Instant.now()));
        var leg = service.fastest(songpa, seongsan, KakaoMapClient.Transport.CAR).orElseThrow();
        assertThat(leg.minutes()).isEqualTo(40 + 60 + 65 + 20 + 50);
        assertThat(leg.basis()).contains("김포국제공항 → 제주국제공항").contains("65분").contains("오늘 운항 기준");

        // 운항편이 하나도 없으면 비어 있고, 활용신청이 안 된 키면 다른 조합을 더 묻지 않는다.
        when(flights.flights(anyString(), anyString(), any())).thenReturn(List.of());
        assertThat(service.fastest(songpa, seongsan, KakaoMapClient.Transport.CAR)).isEmpty();
        when(flights.flights(anyString(), anyString(), any())).thenThrow(new RecommendationException("FLIGHT_AUTH", "인증"));
        clearInvocations(flights);
        assertThat(service.fastest(songpa, seongsan, KakaoMapClient.Transport.CAR)).isEmpty();
        verify(flights, times(1)).flights(anyString(), anyString(), any());
    }

    // 안내는 항공사별로 묶어 편수·첫 편·막 편·가장 짧은 비행시간·최저 일반석 요금을 내고, 빠른 항공사부터 보여 준다.
    @Test void groupsFlightsByAirline() {
        var routes = FlightGuideService.routes(List.of(flight("아시아나 항공", 6, 30, 75, 61900), flight("대한항공", 7, 0, 65, null),
            flight("아시아나 항공", 20, 10, 70, 59000), flight("대한항공", 21, 0, 70, 80000)));
        assertThat(routes).hasSize(2);
        assertThat(routes.getFirst().airline()).isEqualTo("대한항공");
        assertThat(routes.getFirst().fastestMinutes()).isEqualTo(65);
        assertThat(routes.getFirst().minCharge()).isEqualTo(80000);
        assertThat(routes.get(1)).satisfies(r -> {
            assertThat(r.flights()).isEqualTo(2);
            assertThat(r.firstDeparture()).isEqualTo("06:30");
            assertThat(r.lastDeparture()).isEqualTo("20:10");
            assertThat(r.minCharge()).isEqualTo(59000);
        });
    }
}
