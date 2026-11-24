package com.travel.travel_recommendation.service;

import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class FerryGuideServiceTests {
    private final FerryClient ferries = mock(FerryClient.class);
    private final KakaoMapClient maps = mock(KakaoMapClient.class);
    private final FerryGuideService service = new FerryGuideService(ferries, maps);
    private final KakaoMapClient.Location songpa = new KakaoMapClient.Location("서울특별시 송파구", 127.1, 37.5, Instant.now());
    private final KakaoMapClient.Location nari = new KakaoMapClient.Location("경북 울릉군 북면 나리", 130.87, 37.52, Instant.now());

    private static FerryClient.Sailing sailing(String from, String to, int depHour, int minutes) {
        var departure = LocalDate.now(ZoneId.of("Asia/Seoul")).atTime(depHour, 0);
        return new FerryClient.Sailing(from, to, departure, departure.plusMinutes(minutes), "테스트호");
    }
    private static KakaoMapClient.TravelTime minutes(int minutes) {
        return new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR, minutes * 60, 1000, Instant.now());
    }

    // 울릉도는 경도로 나눈다. 육지에서 가장 동쪽인 호미곶도 육지로 본다.
    @Test void ulleungIsSplitByLongitude() {
        assertThat(FerryPorts.onUlleung(nari)).isTrue();
        assertThat(FerryPorts.onUlleung(songpa)).isFalse();
        assertThat(FerryPorts.onUlleung(new KakaoMapClient.Location("호미곶", 129.57, 36.08, Instant.now()))).isFalse();
    }

    // 편도 시간 = 집→항구 + 승선 대기 30분 + 가장 짧은 배 + 하선 10분 + 항구→관광지. 가까운 항구(묵호)라도 배가 오래 걸리면 다른 항구(포항)를 쓴다.
    @Test void sumsGroundLegsWaitingAndShortestSailing() {
        when(ferries.configured()).thenReturn(true);
        when(ferries.sailings(eq("SEA43010"), any())).thenReturn(List.of(sailing("포항", "울릉_도동", 9, 140), sailing("포항", "독도", 7, 200)));
        when(ferries.sailings(eq("SEA44030"), any())).thenReturn(List.of(sailing("묵호", "울릉_도동", 8, 476)));
        when(maps.travelTime(eq(songpa), any(), eq(KakaoMapClient.Transport.CAR)))
            .thenAnswer(call -> minutes(call.<KakaoMapClient.Location>getArgument(1).address().startsWith("묵호") ? 180 : 270));
        when(maps.travelTime(any(), eq(nari), eq(KakaoMapClient.Transport.CAR))).thenReturn(minutes(20));
        var leg = service.fastest(songpa, nari, KakaoMapClient.Transport.CAR).orElseThrow();
        assertThat(leg.minutes()).isEqualTo(270 + 30 + 140 + 10 + 20);
        assertThat(leg.basis()).contains("포항여객선터미널 → 울릉(도동)여객선터미널").contains("2시간 20분").contains("오늘 운항 기준").contains("결항").contains("자동차");
        // 운항편이 없는 항구까지는 길을 찾지 않는다.
        verify(maps, times(2)).travelTime(eq(songpa), any(), any());
    }

    // 섬에서 나올 때는 울릉도 항구에서 떠나는 편을 본다.
    @Test void leavingTheIslandUsesUlleungPorts() {
        when(ferries.configured()).thenReturn(true);
        when(ferries.sailings(eq("SEA43113"), any())).thenReturn(List.of(sailing("울릉_도동", "포항", 14, 140)));
        when(maps.travelTime(eq(nari), any(), any())).thenReturn(minutes(20));
        when(maps.travelTime(any(), eq(songpa), any())).thenReturn(minutes(270));
        var leg = service.fastest(nari, songpa, KakaoMapClient.Transport.CAR).orElseThrow();
        assertThat(leg.minutes()).isEqualTo(20 + 30 + 140 + 10 + 270);
        assertThat(leg.basis()).contains("울릉(도동)여객선터미널 → 포항여객선터미널");
        verify(ferries, never()).sailings(eq("SEA43010"), any());
    }

    // 키가 없거나 활용신청 전이면 더 묻지 않고 비우며, 지도 인증 오류는 그대로 알린다.
    @Test void stopsOnAuthErrors() {
        assertThat(service.fastest(songpa, nari, KakaoMapClient.Transport.CAR)).isEmpty();
        verify(ferries, never()).sailings(any(), any());

        when(ferries.configured()).thenReturn(true);
        when(ferries.sailings(any(), any())).thenThrow(new RecommendationException("FERRY_AUTH", "인증"));
        assertThat(service.fastest(songpa, nari, KakaoMapClient.Transport.CAR)).isEmpty();
        verify(ferries, times(1)).sailings(any(), any());

        reset(ferries);
        when(ferries.configured()).thenReturn(true);
        when(ferries.sailings(eq("SEA43010"), any())).thenReturn(List.of(sailing("포항", "울릉_도동", 9, 140)));
        when(maps.travelTime(any(), any(), any())).thenThrow(new RecommendationException("MAP_AUTH", "지도 인증"));
        assertThatThrownBy(() -> service.fastest(songpa, nari, KakaoMapClient.Transport.CAR))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("MAP_AUTH"));
    }
}
