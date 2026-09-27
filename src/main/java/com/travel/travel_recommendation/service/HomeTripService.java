package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.HomeTrip;
import com.travel.travel_recommendation.dto.PlaceCandidateView;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 첫날 집 출발·마지막 날 귀가 설정(2026-09-24 결정)을 만든다. 출발지와 돌아갈 곳은 따로 받으며, 돌아갈 곳을 비우면 출발지로 돌아온다.
 * 편도 이동시간은 사용자가 직접 적은 값이 먼저이고, 없으면 자동차는 카카오 길찾기, 대중교통은 카카오 대중교통 길찾기 → 직행 시외·고속버스 순서로 찾는다.
 * 찾지 못하거나 제주처럼 비행기·배로 가야 하면 추정하지 않고 직접 적어 달라고 한다. 좌표는 조회에만 쓰고 저장하지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class HomeTripService {
    private final KakaoMapClient maps;
    private final BusGuideService buses;

    /**
     * 일정 입력 화면에서 받은 값. 출발지 좌표는 '내 위치로 찾기'를 눌렀을 때만 오고, minutes는 직접 적은 여행지까지 편도 시간(분)이다.
     * returnPlace·returnMinutes는 마지막 날 돌아갈 곳과 그곳까지 편도 시간이며 비우면 출발지와 같게 본다.
     */
    public record Request(String origin, Double longitude, Double latitude, LocalTime departAt, LocalTime arriveBy, Integer minutes,
                         String returnPlace, Integer returnMinutes) {
        public Request(String origin, Double longitude, Double latitude, LocalTime departAt, LocalTime arriveBy, Integer minutes) {
            this(origin, longitude, latitude, departAt, arriveBy, minutes, null, null);
        }
        public boolean located() { return longitude != null && latitude != null; }
        public boolean typed() { return origin != null && !origin.isBlank(); }
        public boolean separateReturn() { return returnPlace != null && !returnPlace.isBlank(); }
    }

    /** 한 방향 이동: 보여 줄 장소 이름, 편도 분, 근거, 조회 시각. */
    private record Leg(String place, int minutes, String basis, Instant checkedAt) {}

    public HomeTrip resolve(Request request, PlaceCandidateView places, KakaoMapClient.Transport transport) {
        if (request == null || request.departAt() == null || request.arriveBy() == null)
            throw failure("HOME_REQUIRED", "첫날 집에서 출발할 시각과 마지막 날 집에 도착할 시각을 적어 주세요.");
        checkMinutes(request.minutes(), "여행지까지");
        checkMinutes(request.returnMinutes(), "돌아갈 곳까지");
        String destination = places.destination().name();
        var target = center(places);

        // 가는 길: 출발지(적은 이름 또는 현재 위치) → 여행지
        Leg out;
        if (!request.located() && !request.typed()) {
            if (request.minutes() == null) throw failure("HOME_ORIGIN", "출발지(집)를 적거나 '내 위치로 찾기'를 눌러 주세요.");
            out = new Leg("집", request.minutes(), "직접 입력한 편도 시간", null);
        } else {
            var origin = request.located() ? maps.originAt(request.longitude(), request.latitude()) : maps.findOrigin(request.origin());
            out = leg(origin, request.minutes(), target, destination, transport, true);
        }

        // 오는 길: 돌아갈 곳을 따로 적었으면 그곳까지 따로 찾고, 아니면 출발지로 돌아온다(편도 시간만 따로 적었으면 그 값).
        Leg back;
        if (request.separateReturn()) {
            back = leg(maps.findOrigin(request.returnPlace()), request.returnMinutes(), target, destination, transport, false);
        } else if (request.returnMinutes() != null) {
            back = new Leg(out.place(), request.returnMinutes(), "직접 입력한 편도 시간", null);
        } else {
            back = out;
        }
        return new HomeTrip(out.place(), request.departAt(), request.arriveBy(), out.minutes(), out.basis(), out.checkedAt(),
            back.place(), back.minutes(), back.basis());
    }

    /**
     * 집(place)과 여행지 사이 편도 시간. 직접 적은 시간이 있으면 그 값을, 없으면 길찾기 → 버스 순서로 찾는다.
     * 오는 길(outbound=false)은 여행지 → 집 방향으로 길을 찾고, 버스는 운행표가 여행지행만 있어 같은 구간의 최단 소요를 쓴다.
     */
    private Leg leg(KakaoMapClient.Origin home, Integer typed, Target target, String destination, KakaoMapClient.Transport transport, boolean outbound) {
        if (typed != null) return new Leg(home.label(), typed, "직접 입력한 편도 시간", null);
        if (target == null)
            throw failure("HOME_MINUTES", destination + " 관광지 위치를 확인하지 못해 이동시간을 계산하지 못했어요. 편도 이동시간을 직접 적어 주세요.");
        // 제주를 오가는 길은 비행기나 배를 타야 한다. 카카오 자동차 길찾기는 카페리 구간을 넣어 '성공'으로 답하므로
        // 자동으로 찾지 않고, 공항·항구 이동과 탑승 대기까지 포함한 시간을 직접 적어 달라고 한다.
        if (onJejuIsland(home.location()) != onJejuIsland(target.location()))
            throw failure("HOME_MINUTES", (outbound ? home.label() + "에서 " + destination : destination + "에서 " + home.label())
                + "까지는 비행기나 배로 가야 해서 이동시간을 자동으로 찾지 않아요. 공항·항구까지 가는 시간과 탑승 대기를 포함한 편도 시간을 직접 적어 주세요.");
        // 여행지 관광지들의 가운데까지 조회한다. 첫 관광지까지와는 조금 다를 수 있다고 일정에 적는다.
        try {
            var time = outbound ? maps.travelTime(home.location(), target.location(), transport)
                : maps.travelTime(target.location(), home.location(), transport);
            if (time.minutes() <= 600) return new Leg(home.label(), time.minutes(),
                (transport == KakaoMapClient.Transport.CAR ? "자동차" : "대중교통") + " · 카카오 길찾기, " + target.name() + " 부근 기준", time.checkedAt());
        } catch (RecommendationException error) {
            if ("MAP_AUTH".equals(error.getCode())) throw error;
        }
        if (transport == KakaoMapClient.Transport.PUBLIC_TRANSIT && home.city() != null) {
            var bus = buses.fastestMinutes(home.city(), destination);
            if (bus.isPresent()) return new Leg(home.label(), bus.getAsInt(),
                "직행 시외·고속버스 최단 소요" + (outbound ? "" : "(여행지행 기준)") + ", 집 ↔ 터미널 이동 제외", Instant.now());
        }
        throw failure("HOME_MINUTES", (outbound ? home.label() + "에서 " + destination : destination + "에서 " + home.label()) + "까지 "
            + (transport == KakaoMapClient.Transport.CAR ? "자동차 경로" : "대중교통·직행 버스") + "를 찾지 못했어요. 편도 이동시간을 직접 적어 주세요.");
    }

    /**
     * 제주도(추자도·마라도 포함)는 모두 북위 34도 남쪽에 있고, 육지에서 가장 남쪽인 해남 땅끝은 약 34.3도라 위도만으로 나눈다.
     * 거제·여수처럼 다리로 이어진 곳은 육지와 같이 본다.
     */
    static boolean onJejuIsland(KakaoMapClient.Location location) {
        return location.latitude() < 34.0;
    }

    private static void checkMinutes(Integer minutes, String what) {
        if (minutes != null && (minutes < 10 || minutes > 600))
            throw failure("HOME_MINUTES", what + " 편도 이동시간은 10~600분으로 적어 주세요.");
    }

    private record Target(String name, KakaoMapClient.Location location) {}
    /** 관광지 후보 가운데와 가장 가까운 관광지. 좌표가 있는 후보가 없으면 null이다. */
    private static Target center(PlaceCandidateView places) {
        return TripConstraints.nearCenterFirst(places.attractions()).stream()
            .filter(p -> p.longitude() != null && p.latitude() != null).findFirst()
            .map(p -> new Target(p.name(), new KakaoMapClient.Location(p.address(), p.longitude(), p.latitude(), Instant.now())))
            .orElse(null);
    }

    private static RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
