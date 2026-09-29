package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HomeTripServiceTests {
    private final KakaoMapClient maps = mock(KakaoMapClient.class);
    private final BusGuideService buses = mock(BusGuideService.class);
    private final HomeTripService service = new HomeTripService(maps, buses);
    private final KakaoMapClient.Origin songpa = new KakaoMapClient.Origin("서울특별시 송파구", "서울",
        new KakaoMapClient.Location("서울특별시 송파구", 127.1, 37.5, Instant.now()));

    private PlaceCandidateView places() {
        var attraction = new PlaceCandidate(1L, "경포대", "강원 강릉시 경포로 1", "여행 > 관광,명소", "", "https://place.map.kakao.com/1",
            "", LocalDateTime.of(2026, 9, 1, 0, 0), 128.9, 37.8, "KAKAO", null);
        return new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(9L, "강릉", "강원", "", "https://www.gn.go.kr/",
            LocalDateTime.of(2026, 9, 1, 0, 0)), 2, true, List.of(attraction), List.of(), List.of());
    }
    private HomeTripService.Request request(String origin, Integer minutes) {
        return new HomeTripService.Request(origin, null, null, LocalTime.of(7, 0), LocalTime.of(20, 0), minutes);
    }

    // 자동차는 출발지에서 여행지 관광지 가운데까지 카카오 길찾기로 찾고, 출발지는 시·구 이름만 남긴다.
    @Test void carUsesKakaoRouteToTheDestination() {
        when(maps.findOrigin("송파구 잠실동")).thenReturn(songpa);
        when(maps.travelTime(any(), any(), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 150 * 60, 230000, Instant.now()));
        var home = service.resolve(request("송파구 잠실동", null), places(), KakaoMapClient.Transport.CAR);
        assertThat(home.origin()).isEqualTo("서울특별시 송파구");
        assertThat(home.minutes()).isEqualTo(150);
        assertThat(home.basis()).contains("카카오 길찾기").contains("경포대");
        verifyNoInteractions(buses);
    }

    // 직접 적은 편도 시간이 자동 조회보다 먼저다. 출발지를 적지 않았으면 '집'으로 보여 준다.
    @Test void typedMinutesWinAndNeedNoLookup() {
        var home = service.resolve(request(null, 200), places(), KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(home.minutes()).isEqualTo(200);
        assertThat(home.origin()).isEqualTo("집");
        assertThat(home.basis()).isEqualTo("직접 입력한 편도 시간");
        verifyNoInteractions(maps, buses);
    }

    // 대중교통 길찾기가 없으면 직행 시외·고속버스 최단 소요를 쓰고, 그것도 없으면 추정하지 않고 직접 적어 달라고 한다.
    @Test void transitFallsBackToBusThenAsksForMinutes() {
        when(maps.findOrigin("잠실")).thenReturn(songpa);
        when(maps.travelTime(any(), any(), eq(KakaoMapClient.Transport.PUBLIC_TRANSIT)))
            .thenThrow(new RecommendationException("MAP_NO_ROUTE", "경로 없음"));
        when(buses.fastestMinutes("서울", "강릉")).thenReturn(OptionalInt.of(165));
        var home = service.resolve(request("잠실", null), places(), KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(home.minutes()).isEqualTo(165);
        assertThat(home.basis()).contains("집 ↔ 터미널 이동 제외");

        when(buses.fastestMinutes("서울", "강릉")).thenReturn(OptionalInt.empty());
        assertThatThrownBy(() -> service.resolve(request("잠실", null), places(), KakaoMapClient.Transport.PUBLIC_TRANSIT))
            .isInstanceOfSatisfying(RecommendationException.class, e -> {
                assertThat(e.getCode()).isEqualTo("HOME_MINUTES");
                assertThat(e.getMessage()).contains("편도 이동시간을 직접 적어 주세요");
            });
    }

    // 돌아갈 곳을 따로 적으면 여행지 → 그곳 방향으로 따로 찾고, 비우면 여행지 → 출발지 방향을 따로 찾아 돌아온다.
    @Test void returnPlaceIsLookedUpSeparately() {
        var daejeon = new KakaoMapClient.Origin("대전광역시 서구", "대전", new KakaoMapClient.Location("대전광역시 서구", 127.38, 36.35, Instant.now()));
        when(maps.findOrigin("송파구")).thenReturn(songpa);
        when(maps.findOrigin("대전 서구")).thenReturn(daejeon);
        when(maps.travelTime(eq(songpa.location()), any(), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 150 * 60, 230000, Instant.now()));
        when(maps.travelTime(any(), eq(daejeon.location()), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 190 * 60, 280000, Instant.now()));
        var request = new HomeTripService.Request("송파구", null, null, LocalTime.of(7, 0), LocalTime.of(20, 0), null, "대전 서구", null);
        var home = service.resolve(request, places(), KakaoMapClient.Transport.CAR);
        assertThat(home.origin()).isEqualTo("서울특별시 송파구");
        assertThat(home.minutes()).isEqualTo(150);
        assertThat(home.returnPlace()).isEqualTo("대전광역시 서구");
        assertThat(home.returnMinutes()).isEqualTo(190);

        // 같은 곳으로 돌아와도 오는 길(170분)은 가는 길(150분)과 따로 조회한다.
        when(maps.travelTime(any(), eq(songpa.location()), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 170 * 60, 230000, Instant.now()));
        var same = service.resolve(request("송파구", null), places(), KakaoMapClient.Transport.CAR);
        assertThat(same.returnPlace()).isEqualTo(same.origin());
        assertThat(same.minutes()).isEqualTo(150);
        assertThat(same.returnMinutes()).isEqualTo(170);
    }

    // 제주를 오가는 길은 카카오가 카페리 경로를 자동차 '성공'으로 답하므로 조회하지 않고 직접 적어 달라고 한다. 적으면 그 값을 쓴다.
    @Test void jejuTripAsksForFlightOrShipMinutes() {
        var attraction = new PlaceCandidate(2L, "성산일출봉", "제주 서귀포시 성산읍", "여행 > 관광,명소", "", "https://place.map.kakao.com/2",
            "", LocalDateTime.of(2026, 9, 1, 0, 0), 126.94, 33.46, "KAKAO", null);
        var jeju = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(10L, "제주", "제주", "", "https://www.visitjeju.net/",
            LocalDateTime.of(2026, 9, 1, 0, 0)), 3, true, List.of(attraction), List.of(), List.of());
        when(maps.findOrigin("송파구")).thenReturn(songpa);
        assertThatThrownBy(() -> service.resolve(request("송파구", null), jeju, KakaoMapClient.Transport.CAR))
            .isInstanceOfSatisfying(RecommendationException.class, e -> {
                assertThat(e.getCode()).isEqualTo("HOME_MINUTES");
                assertThat(e.getMessage()).contains("비행기나 배");
            });
        verify(maps, never()).travelTime(any(), any(), any());

        assertThat(service.resolve(request("송파구", 180), jeju, KakaoMapClient.Transport.CAR).minutes()).isEqualTo(180);

        // 제주에 사는 사람이 제주를 여행하면 섬 안의 길이라 그대로 찾는다.
        var jejuCity = new KakaoMapClient.Origin("제주특별자치도 제주시", "제주", new KakaoMapClient.Location("제주특별자치도 제주시", 126.53, 33.50, Instant.now()));
        when(maps.findOrigin("제주시")).thenReturn(jejuCity);
        when(maps.travelTime(any(), any(), eq(KakaoMapClient.Transport.CAR)))
            .thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, 60 * 60, 45000, Instant.now()));
        assertThat(service.resolve(request("제주시", null), jeju, KakaoMapClient.Transport.CAR).minutes()).isEqualTo(60);
    }

    @Test void missingTimesOrOriginAreAskedFor() {
        assertThatThrownBy(() -> service.resolve(new HomeTripService.Request("잠실", null, null, null, LocalTime.of(20, 0), null),
            places(), KakaoMapClient.Transport.CAR)).hasMessageContaining("집에서 출발할 시각");
        assertThatThrownBy(() -> service.resolve(request(null, null), places(), KakaoMapClient.Transport.CAR))
            .hasMessageContaining("출발지(집)를 적거나");
        assertThatThrownBy(() -> service.resolve(request(null, 5), places(), KakaoMapClient.Transport.CAR))
            .hasMessageContaining("10~600분");
    }
}
