package com.travel.travel_recommendation.service;

import java.time.Instant;
import java.util.*;

/**
 * 국내 공항 15곳(TAGO 공항 목록 기준). id는 TAGO 공항 코드이고, 좌표는 여객터미널 부근의 대략값이다.
 * 집·관광지에서 가까운 공항을 고르고 공항까지의 길찾기 출발·도착점으로만 쓴다.
 */
public final class Airports {
    private Airports() {}

    public record Airport(String id, String name, double longitude, double latitude) {
        /** 길찾기에 쓰는 위치. 주소 대신 공항 이름을 둔다. */
        public KakaoMapClient.Location location() { return new KakaoMapClient.Location(name, longitude, latitude, Instant.now()); }
    }

    public static final List<Airport> ALL = List.of(
        new Airport("NAARKSS", "김포국제공항", 126.7937, 37.5586),
        new Airport("NAARKSI", "인천국제공항", 126.4505, 37.4602),
        new Airport("NAARKPK", "김해국제공항", 128.9380, 35.1796),
        new Airport("NAARKPC", "제주국제공항", 126.4930, 33.5104),
        new Airport("NAARKTU", "청주국제공항", 127.4989, 36.7166),
        new Airport("NAARKTN", "대구국제공항", 128.6588, 35.8941),
        new Airport("NAARKJJ", "광주공항", 126.8089, 35.1264),
        new Airport("NAARKJB", "무안국제공항", 126.3828, 34.9914),
        new Airport("NAARKJY", "여수공항", 127.6170, 34.8423),
        new Airport("NAARKPU", "울산공항", 129.3518, 35.5935),
        new Airport("NAARKTH", "포항경주공항", 129.4204, 35.9879),
        new Airport("NAARKPS", "사천공항", 128.0703, 35.0886),
        new Airport("NAARKJK", "군산공항", 126.6158, 35.9038),
        new Airport("NAARKNW", "원주공항", 127.9603, 37.4381),
        new Airport("NAARKNY", "양양국제공항", 128.6690, 38.0613));

    public static Optional<Airport> byId(String id) {
        return ALL.stream().filter(a -> a.id().equals(id)).findFirst();
    }

    /** location에서 직선거리가 가까운 순서로 limit곳. */
    public static List<Airport> nearest(KakaoMapClient.Location location, int limit) {
        return ALL.stream()
            .sorted(Comparator.comparingDouble(a -> kilometers(location.longitude(), location.latitude(), a.longitude(), a.latitude())))
            .limit(limit).toList();
    }

    /** 두 좌표 사이 직선거리(km). 국내 범위에서는 위도 1도 ≈ 111km, 경도는 위도의 코사인만큼 줄여 계산한다. */
    static double kilometers(double longitude1, double latitude1, double longitude2, double latitude2) {
        double dx = (longitude2 - longitude1) * Math.cos(Math.toRadians((latitude1 + latitude2) / 2)) * 111;
        double dy = (latitude2 - latitude1) * 111;
        return Math.hypot(dx, dy);
    }
}
