package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidateView;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * 화면 지도(SFR-11)에 넘길 좌표와 카카오맵 길찾기 링크를 만든다.
 * 서버가 이미 확인한 좌표만 사용하고, 좌표가 없는 장소는 지도에 올리지 않고 목록으로 알린다.
 */
public final class TripMap {
    private TripMap() {}

    public record Point(int order, String name, String type, double lat, double lng) {}
    public record DayRoute(int number, List<Point> stops) {}
    /** 일정 지도: 일차별 방문 순서와 이동 구간별 길찾기 링크(키: "일차-항목순번"). */
    public record Route(String mode, List<DayRoute> days, Map<String, String> links, int missing) {}
    public record Pin(String name, String type, double lat, double lng) {}
    public record Pending(String name, String type, String address) {}
    /** 장소 목록 지도: 좌표가 확인된 장소와, 주소로만 찾을 수 있는 장소. */
    public record Places(List<Pin> pins, List<Pending> pending) {}
    /** 일정 항목에서 지도에 필요한 값만 받는다. 초안과 저장 일정 모두 같은 형태로 넘긴다. */
    public record Stop(String type, String name, String mapUrl) {}
    public record Day(int number, List<Stop> items) {}

    private static final String MAP_LINK = "https://map.kakao.com/link/map/";

    public static Route route(List<Day> days, boolean car) {
        String mode = car ? "car" : "traffic";
        var result = new ArrayList<DayRoute>(); var links = new LinkedHashMap<String, String>();
        Coordinate previous = null; String previousName = null; int missing = 0;
        for (var day : days) {
            var stops = new ArrayList<Point>();
            for (int i = 0; i < day.items().size(); i++) {
                var item = day.items().get(i);
                var at = coordinate(item.mapUrl());
                if ("TRANSIT".equals(item.type())) {
                    // 이동 항목의 위치 링크는 도착 장소를 가리킨다. 출발은 바로 앞에 머문 장소다.
                    String target = arrival(item.name());
                    // 같은 건물 안 이동처럼 출발·도착 좌표가 같으면 길찾기로 연결하지 않는다.
                    if (previous != null && at != null && !previous.equals(at))
                        links.put(day.number() + "-" + i, "https://map.kakao.com/link/by/" + mode + "/"
                            + encode(previousName) + "," + previous.lat() + "," + previous.lng() + "/"
                            + encode(target) + "," + at.lat() + "," + at.lng());
                    continue;
                }
                if (at == null) { if (!"FREE".equals(item.type())) missing++; continue; }
                previous = at; previousName = item.name();
                if ("FREE".equals(item.type())) continue;
                // 자유시간 뒤 같은 식당에서 식사하는 경우처럼 같은 위치가 연속되면 한 번만 표시한다.
                if (!stops.isEmpty() && stops.getLast().lat() == at.lat() && stops.getLast().lng() == at.lng()
                        && stops.getLast().name().equals(item.name())) continue;
                stops.add(new Point(stops.size() + 1, item.name(), item.type(), at.lat(), at.lng()));
            }
            result.add(new DayRoute(day.number(), List.copyOf(stops)));
        }
        return new Route(mode, List.copyOf(result), Collections.unmodifiableMap(links), missing);
    }

    public static Places places(PlaceCandidateView view) {
        var pins = new ArrayList<Pin>(); var pending = new ArrayList<Pending>();
        add(view.attractions(), "ATTRACTION", pins, pending);
        add(view.accommodations(), "ACCOMMODATION", pins, pending);
        return new Places(List.copyOf(pins), List.copyOf(pending));
    }

    private static void add(List<com.travel.travel_recommendation.dto.PlaceCandidate> list, String type, List<Pin> pins, List<Pending> pending) {
        for (var place : list) {
            if (place.latitude() != null && place.longitude() != null && valid(place.latitude(), place.longitude()))
                pins.add(new Pin(place.name(), type, place.latitude(), place.longitude()));
            else pending.add(new Pending(place.name(), type, place.address()));
        }
    }

    private record Coordinate(double lat, double lng) {}

    // 서버가 만든 "https://map.kakao.com/link/map/이름,위도,경도" 형식에서만 좌표를 읽는다.
    private static Coordinate coordinate(String mapUrl) {
        if (mapUrl == null || !mapUrl.startsWith(MAP_LINK)) return null;
        var parts = mapUrl.substring(MAP_LINK.length()).split(",");
        if (parts.length < 3) return null;
        try {
            double lat = Double.parseDouble(parts[parts.length - 2]), lng = Double.parseDouble(parts[parts.length - 1]);
            return valid(lat, lng) ? new Coordinate(lat, lng) : null;
        } catch (NumberFormatException invalid) {
            return null;
        }
    }
    // 국내 여행 범위의 좌표만 받는다.
    private static boolean valid(double lat, double lng) {
        return Double.isFinite(lat) && Double.isFinite(lng) && lat >= 33 && lat <= 39 && lng >= 124 && lng <= 132;
    }
    private static String arrival(String transitName) {
        int arrow = transitName == null ? -1 : transitName.lastIndexOf(" → ");
        return arrow < 0 ? "도착지" : transitName.substring(arrow + 3);
    }
    private static String encode(String name) {
        return URLEncoder.encode(name == null || name.isBlank() ? "장소" : name, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
