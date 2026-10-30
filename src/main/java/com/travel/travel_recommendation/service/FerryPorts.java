package com.travel.travel_recommendation.service;

import java.time.Instant;
import java.util.List;

/**
 * 울릉도를 오가는 여객선 항구. id·name은 TAGO 국내선박운항정보 항구 목록의 항구 ID·이름이고(운항편의 도착지 이름과 맞춰 본다),
 * 좌표는 카카오 장소 검색으로 확인한 여객선터미널 위치다(2026-10-30). 항구까지·항구에서의 길찾기 출발·도착점으로만 쓴다.
 * 시·군 단위 여행지 중 배로만 갈 수 있는 곳은 울릉뿐이라 울릉 노선만 둔다. 제주는 비행기로 찾는다(HomeTripService).
 */
public final class FerryPorts {
    private FerryPorts() {}

    /** label은 화면 근거에 보여 줄 터미널 이름이다. */
    public record Port(String id, String name, String label, double longitude, double latitude) {
        public KakaoMapClient.Location location() { return new KakaoMapClient.Location(label, longitude, latitude, Instant.now()); }
    }

    /** 울릉도로 가는 배가 뜨는 육지 항구. */
    public static final List<Port> MAINLAND = List.of(
        new Port("SEA43010", "포항", "포항여객선터미널", 129.3788, 36.0515),
        new Port("SEA43011", "포항영일만", "포항영일만항 여객터미널", 129.4395, 36.0960),
        new Port("SEA43030", "후포", "후포여객선터미널", 129.4615, 36.6787),
        new Port("SEA44030", "묵호", "묵호항여객선터미널", 129.1125, 37.5503),
        new Port("SEA44060", "강릉", "강릉항여객터미널", 128.9528, 37.7728));

    /** 울릉도 항구. */
    public static final List<Port> ULLEUNG = List.of(
        new Port("SEA43113", "울릉_도동", "울릉(도동)여객선터미널", 130.9097, 37.4815),
        new Port("SEA43111", "울릉_저동", "저동항여객선터미널", 130.9098, 37.4962),
        new Port("SEA43112", "울릉_사동", "울릉(사동)항여객선터미널", 130.8783, 37.4617));

    /** 울릉도·독도는 동경 130.5도보다 동쪽에 있고, 육지에서 가장 동쪽인 포항 호미곶은 약 129.6도라 경도만으로 나눈다. */
    public static boolean onUlleung(KakaoMapClient.Location location) {
        return location.longitude() > 130.5;
    }
}
