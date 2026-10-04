package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.TripMap.*;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TripMapTests {
    private static String map(String name, double lat, double lng) {
        return "https://map.kakao.com/link/map/" + java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20") + "," + lat + "," + lng;
    }

    @Test void routeNumbersStopsPerDayAndLinksEachMoveFromPreviousStop() {
        var day1 = new TripMap.Day(1, List.of(
            new Stop("VISIT", "경포대", map("경포대", 37.79, 128.90)),
            new Stop("TRANSIT", "경포대 → 식당, 강릉", map("식당, 강릉", 37.77, 128.88)),
            new Stop("FREE", "식사 전 자유시간", map("식당, 강릉", 37.77, 128.88)),
            new Stop("MEAL", "식당, 강릉", map("식당, 강릉", 37.77, 128.88)),
            new Stop("TRANSIT", "식당, 강릉 → 호텔", map("호텔", 37.75, 128.92)),
            new Stop("STAY", "호텔", map("호텔", 37.75, 128.92))));
        var day2 = new TripMap.Day(2, List.of(
            new Stop("TRANSIT", "호텔 → 오죽헌", map("오죽헌", 37.78, 128.87)),
            new Stop("VISIT", "오죽헌", map("오죽헌", 37.78, 128.87))));
        var route = TripMap.route(List.of(day1, day2), false);

        assertThat(route.mode()).isEqualTo("traffic");
        assertThat(route.days().getFirst().stops()).extracting(Point::order, Point::item, Point::name)
            .containsExactly(tuple(1, 0, "경포대"), tuple(2, 3, "식당, 강릉"), tuple(3, 5, "호텔"));
        assertThat(route.days().get(1).stops()).extracting(Point::name).containsExactly("오죽헌");
        assertThat(route.links()).containsOnlyKeys("1-1", "1-4", "2-0");
        // 쉼표가 들어간 이름도 인코딩해 좌표와 섞이지 않는다.
        assertThat(route.links().get("1-1")).isEqualTo("https://map.kakao.com/link/by/traffic/%EA%B2%BD%ED%8F%AC%EB%8C%80,37.79,128.9/%EC%8B%9D%EB%8B%B9%2C%20%EA%B0%95%EB%A6%89,37.77,128.88");
        // 다음 날 첫 이동은 전날 숙소에서 출발한다.
        assertThat(route.links().get("2-0")).startsWith("https://map.kakao.com/link/by/traffic/%ED%98%B8%ED%85%94,37.75,128.92/");
        assertThat(route.missing()).isZero();
    }

    // 도로 경로를 찾는 방법을 주면 이웃한 방문지 사이(legs)를 그 경로로 채우고, 주지 않으면 빈 목록(직선)으로 둔다.
    @Test void legsCarryRoadPathsBetweenNeighbouringStops() {
        var day = new TripMap.Day(1, List.of(
            new Stop("VISIT", "경포대", map("경포대", 37.79, 128.90)),
            new Stop("TRANSIT", "경포대 → 오죽헌", map("오죽헌", 37.78, 128.87)),
            new Stop("VISIT", "오죽헌", map("오죽헌", 37.78, 128.87)),
            new Stop("TRANSIT", "오죽헌 → 호텔", map("호텔", 37.75, 128.92)),
            new Stop("STAY", "호텔", map("호텔", 37.75, 128.92))));
        List<double[]> road = List.of(new double[]{37.785, 128.88});
        // 첫 구간(경포대 → 오죽헌)의 경로만 알고 있다고 가정한다.
        var route = TripMap.route(List.of(day), true,
            (fromLat, fromLng, toLat, toLng) -> fromLat == 37.79 && toLat == 37.78 ? road : List.of());
        assertThat(route.days().getFirst().legs()).hasSize(2);
        assertThat(route.days().getFirst().legs().get(0)).containsExactly(new double[]{37.785, 128.88});
        assertThat(route.days().getFirst().legs().get(1)).isEmpty();
        assertThat(TripMap.route(List.of(day), true).days().getFirst().legs()).allSatisfy(leg -> assertThat(leg).isEmpty());
    }

    @Test void itemsWithoutTrustedCoordinatesAreLeftOffTheMap() {
        var day = new TripMap.Day(1, List.of(
            new Stop("VISIT", "좌표 없음", ""),
            new Stop("VISIT", "다른 링크", "https://example.com/link/map/x,37.7,128.9"),
            new Stop("VISIT", "범위 밖", map("범위 밖", 10, 10)),
            new Stop("TRANSIT", "범위 밖 → 경포대", map("경포대", 37.79, 128.90)),
            new Stop("VISIT", "경포대", map("경포대", 37.79, 128.90))));
        var route = TripMap.route(List.of(day), true);
        assertThat(route.days().getFirst().stops()).extracting(Point::name).containsExactly("경포대");
        assertThat(route.missing()).isEqualTo(3);
        var sameBuilding = TripMap.route(List.of(new TripMap.Day(1, List.of(
            new Stop("MEAL", "호텔 식당", map("호텔 식당", 37.75, 128.92)),
            new Stop("TRANSIT", "호텔 식당 → 호텔", map("호텔", 37.75, 128.92)),
            new Stop("STAY", "호텔", map("호텔", 37.75, 128.92))))), true);
        assertThat(sameBuilding.links()).isEmpty();
        // 출발 좌표를 모르면 길찾기 링크를 만들지 않는다.
        assertThat(route.links()).isEmpty();
    }

    @Test void placesSplitConfirmedCoordinatesFromAddressOnlyCandidates() {
        var kakao = new PlaceCandidate(1L, "카카오 관광지", "강원 강릉시 경포로 1", "관광", "", "https://place.map.kakao.com/1", "", LocalDateTime.now(), 128.9, 37.79, "KAKAO");
        var system = new PlaceCandidate(2L, "오죽헌", "강원 강릉시 율곡로3139번길 24", "문화", "", "https://www.gn.go.kr/", "", LocalDateTime.now());
        var hotel = new PlaceCandidate(3L, "호텔", "강원 강릉시 해안로406번길 2", "호텔", "", "https://www.gn.go.kr/", "", LocalDateTime.now());
        var view = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(9L, "강릉", "강원", "", "https://www.gn.go.kr/", LocalDateTime.now()),
            2, true, List.of(kakao, system), List.of(hotel), List.of());
        var places = TripMap.places(view);
        assertThat(places.pins()).extracting(Pin::name, Pin::type).containsExactly(tuple("카카오 관광지", "ATTRACTION"));
        assertThat(places.pending()).extracting(Pending::name, Pending::type).containsExactly(tuple("오죽헌", "ATTRACTION"), tuple("호텔", "ACCOMMODATION"));
    }
}
