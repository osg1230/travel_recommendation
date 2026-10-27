package com.travel.travel_recommendation.service;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class TransitRouteServiceTests {
    private final KakaoMapClient client = mock(KakaoMapClient.class);

    @SuppressWarnings("unchecked")
    private TransitRouteService service(KakaoMapClient available) {
        ObjectProvider<KakaoMapClient> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(available);
        return new TransitRouteService(provider);
    }

    private static TripMap.Point stop(int order, String name, double lat, double lng) {
        return new TripMap.Point(order, order, name, "VISIT", lat, lng);
    }

    // 구간마다 따로 조회하고, 실패한 구간과 같은 위치끼리의 구간은 빈 목록(화면에서 직선)으로 둔다.
    @Test void looksUpEachLegAndLeavesFailedOrSameSpotLegsEmpty() {
        var bus = List.of(new KakaoMapClient.PathPiece("BUS", List.of(new double[]{37.77, 128.87}, new double[]{37.78, 128.88})));
        when(client.transitPath(argThat(at -> at != null && at.latitude() == 37.77), any())).thenReturn(bus);
        when(client.transitPath(argThat(at -> at != null && at.latitude() == 37.78), any()))
            .thenThrow(new RecommendationException("MAP_NO_ROUTE", "경로 없음"));
        var day = new TripMap.DayRoute(2, List.of(stop(1, "경포해변", 37.77, 128.87), stop(2, "오죽헌", 37.78, 128.88),
            stop(3, "카페", 37.79, 128.89), stop(4, "같은 건물 식당", 37.79, 128.89)), List.of());

        var result = service(client).day(day);

        assertThat(result.day()).isEqualTo(2);
        assertThat(result.legs()).hasSize(3);
        assertThat(result.legs().get(0)).isEqualTo(bus);
        assertThat(result.legs().get(1)).isEmpty();
        assertThat(result.legs().get(2)).isEmpty();
        assertThat(result.failed()).isEqualTo(1);
        verify(client, times(2)).transitPath(any(), any());
    }

    @Test void refusesWithoutMapClientOrWithTooManyLegs() {
        var shortDay = new TripMap.DayRoute(1, List.of(stop(1, "가", 37.77, 128.87), stop(2, "나", 37.78, 128.88)), List.of());
        assertThatThrownBy(() -> service(null).day(shortDay))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("MAP_NOT_CONFIGURED"));
        var many = new java.util.ArrayList<TripMap.Point>();
        for (int i = 0; i <= TransitRouteService.MAX_LEGS + 1; i++) many.add(stop(i + 1, "장소" + i, 37.0 + i * 0.01, 128.0));
        assertThatThrownBy(() -> service(client).day(new TripMap.DayRoute(1, many, List.of())))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("MAP_TOO_MANY"));
        verifyNoInteractions(client);
    }
}
