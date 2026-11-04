package com.travel.travel_recommendation.service;

import java.time.Instant;
import java.util.*;

/**
 * 열차 시간을 찾을 때 쓰는 주요 기차역 98곳. id는 TAGO 열차정보의 역 코드(2026-11-04 도시별 기차역 목록으로 확인)이고,
 * 좌표는 같은 날 카카오 장소 검색에서 찾은 역 위치다. TAGO 목록에는 좌표가 없어 KTX·ITX가 서는 역과 지역 중심 역만 골라 적었다.
 * 집·여행지에서 가까운 역을 고르고 역까지의 길찾기 출발·도착점으로만 쓴다.
 */
public final class TrainStations {
    private TrainStations() {}

    public record Station(String id, String name, double longitude, double latitude) {
        /** 길찾기에 쓰는 위치. 주소 대신 역 이름을 둔다. */
        public KakaoMapClient.Location location() { return new KakaoMapClient.Location(name + "역", longitude, latitude, Instant.now()); }
    }

    public static final List<Station> ALL = List.of(
        new Station("NAT010000", "서울", 126.9707, 37.5541),
        new Station("NAT010032", "용산", 126.9648, 37.5301),
        new Station("NAT010091", "영등포", 126.9079, 37.5157),
        new Station("NAT130126", "청량리", 127.0477, 37.5800),
        new Station("NATH30000", "수서", 127.1044, 37.4855),
        new Station("NAT020040", "상봉", 127.0857, 37.5969),
        new Station("NATH10219", "광명", 126.8847, 37.4162),
        new Station("NAT110147", "행신", 126.8341, 37.6119),
        new Station("NAT010415", "수원", 127.0001, 37.2658),
        new Station("NAT010754", "평택", 127.0856, 36.9906),
        new Station("NATH10960", "천안아산", 127.1044, 36.7943),
        new Station("NAT010971", "천안", 127.1468, 36.8091),
        new Station("NAT050044", "오송", 127.3276, 36.6201),
        new Station("NAT011298", "조치원", 127.2963, 36.6011),
        new Station("NAT050114", "청주", 127.3923, 36.6472),
        new Station("NAT011668", "대전", 127.4346, 36.3323),
        new Station("NAT030057", "서대전", 127.4033, 36.3225),
        new Station("NATH12383", "김천구미", 128.1810, 36.1135),
        new Station("NAT012546", "김천", 128.1147, 36.1235),
        new Station("NAT012775", "구미", 128.3310, 36.1283),
        new Station("NAT013271", "동대구", 128.6284, 35.8793),
        new Station("NAT013239", "대구", 128.5958, 35.8762),
        new Station("NAT013189", "서대구", 128.5400, 35.8813),
        new Station("NAT013395", "경산", 128.7276, 35.8194),
        new Station("NAT013841", "밀양", 128.7712, 35.4745),
        new Station("NAT014281", "구포", 128.9971, 35.2055),
        new Station("NAT014445", "부산", 129.0415, 35.1152),
        new Station("NAT750046", "부전", 129.0601, 35.1647),
        new Station("NAT750161", "센텀", 129.1246, 35.1793),
        new Station("NAT750189", "신해운대", 129.1771, 35.1816),
        new Station("NAT750726", "태화강", 129.3533, 35.5388),
        new Station("NATH13717", "울산", 129.1386, 35.5514),
        new Station("NATH13421", "경주", 129.1390, 35.7984),
        new Station("NAT8B0351", "포항", 129.3419, 36.0716),
        new Station("NAT8B0737", "영덕", 129.3774, 36.4103),
        new Station("NAT752540", "울진", 129.3949, 36.9828),
        new Station("NAT630129", "삼척", 129.1787, 37.4301),
        new Station("NAT601485", "동해", 129.1238, 37.4982),
        new Station("NAT601545", "묵호", 129.1077, 37.5468),
        new Station("NAT601774", "정동진", 129.0327, 37.6914),
        new Station("NAT601936", "강릉", 128.8996, 37.7645),
        new Station("NATN10787", "진부", 128.5745, 37.6427),
        new Station("NATN10625", "평창", 128.4297, 37.5624),
        new Station("NATN10428", "둔내", 128.2212, 37.5103),
        new Station("NATN10230", "횡성", 128.0100, 37.4831),
        new Station("NAT021033", "만종", 127.8930, 37.3537),
        new Station("NAT020947", "원주", 127.9219, 37.3159),
        new Station("NAT020864", "서원주", 127.8399, 37.3490),
        new Station("NAT021549", "제천", 128.2055, 37.1279),
        new Station("NAT021784", "단양", 128.3437, 36.9732),
        new Station("NAT022188", "영주", 128.6250, 36.8111),
        new Station("NAT022558", "안동", 128.6749, 36.5745),
        new Station("NAT022844", "의성", 128.6935, 36.3538),
        new Station("NAT023449", "영천", 128.9392, 35.9595),
        new Station("NAT050827", "충주", 127.9092, 36.9760),
        new Station("NAT140873", "춘천", 127.7166, 37.8848),
        new Station("NAT140840", "남춘천", 127.7238, 37.8641),
        new Station("NAT020524", "양평", 127.4914, 37.4928),
        new Station("NAT080147", "온양온천", 127.0032, 36.7805),
        new Station("NAT080402", "예산", 126.8289, 36.6867),
        new Station("NAT080622", "홍성", 126.6805, 36.5994),
        new Station("NAT080952", "대천", 126.5864, 36.3416),
        new Station("NAT081343", "서천", 126.7081, 36.0822),
        new Station("NAT081318", "장항", 126.7149, 36.0399),
        new Station("NAT081388", "군산", 126.7598, 35.9993),
        new Station("NAT030879", "익산", 126.9457, 35.9405),
        new Station("NAT030508", "논산", 127.0925, 36.2073),
        new Station("NAT030254", "계룡", 127.2653, 36.2730),
        new Station("NATH20438", "공주", 127.0968, 36.3325),
        new Station("NAT040257", "전주", 127.1618, 35.8499),
        new Station("NAT040868", "남원", 127.3614, 35.4112),
        new Station("NAT041072", "곡성", 127.3037, 35.2836),
        new Station("NAT041285", "구례구", 127.4526, 35.1634),
        new Station("NAT041595", "순천", 127.5031, 34.9458),
        new Station("NAT041866", "여천", 127.6642, 34.7791),
        new Station("NAT041993", "여수EXPO", 127.7486, 34.7531),
        new Station("NAT881708", "광양", 127.5877, 34.9566),
        new Station("NAT881014", "진주", 128.1179, 35.1507),
        new Station("NAT880345", "마산", 128.5770, 35.2361),
        new Station("NAT880310", "창원", 128.6066, 35.2575),
        new Station("NAT880281", "창원중앙", 128.7013, 35.2424),
        new Station("NAT880177", "진영", 128.7739, 35.2986),
        new Station("NAT881460", "하동", 127.7618, 35.0640),
        new Station("NAT882328", "보성", 127.0816, 34.7670),
        new Station("NAT031314", "정읍", 126.8422, 35.5755),
        new Station("NAT031857", "광주송정", 126.7908, 35.1377),
        new Station("NAT883012", "광주", 126.9093, 35.1653),
        new Station("NAT031998", "나주", 126.7172, 35.0141),
        new Station("NAT032563", "목포", 126.3866, 34.7911),
        new Station("NAT470446", "강진", 126.7722, 34.6286),
        new Station("NAT470312", "해남", 126.6375, 34.6371),
        new Station("NAT031638", "장성", 126.7802, 35.3000),
        new Station("NAT650341", "영월", 128.4808, 37.1824),
        new Station("NAT650978", "태백", 128.9842, 37.1761),
        new Station("NAT610226", "정선", 128.6721, 37.3880),
        new Station("NAT300360", "상주", 128.1638, 36.4104),
        new Station("NAT300600", "점촌", 128.2034, 36.5955),
        new Station("NAT280927", "문경", 128.1106, 36.7206));

    /** location에서 직선거리가 가까운 순서로 limit곳. 거리 계산은 공항 찾기와 같다. */
    public static List<Station> nearest(KakaoMapClient.Location location, int limit) {
        return ALL.stream()
            .sorted(Comparator.comparingDouble(s -> Airports.kilometers(location.longitude(), location.latitude(), s.longitude(), s.latitude())))
            .limit(limit).toList();
    }
}