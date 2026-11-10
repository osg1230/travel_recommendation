package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.HomeTrip;
import com.travel.travel_recommendation.dto.PlaceCandidateView;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 첫날 집 출발·마지막 날 귀가 설정(2026-09-24 결정)을 만든다. 출발지와 돌아갈 곳은 따로 받으며, 돌아갈 곳을 비우면 출발지로 돌아온다.
 * 편도 이동시간은 사용자가 직접 적은 값이 먼저이고, 없으면 자동차는 카카오 길찾기, 대중교통은 카카오 대중교통 길찾기를 쓴다.
 * 카카오가 장거리 대중교통 경로를 찾지 못하거나 2시간 이상 걸린다고 하면 직행 시외·고속버스와 기차(역까지 이동 + 승차 대기 + 열차)도 찾아 가장 빠른 쪽을 쓴다.
 * 자동차는 가운데 관광지까지 경로가 없으면 다음으로 가까운 관광지 두 곳까지 더 찾아 본다.
 * 바다를 건너는 여행은 제주는 비행기(공항까지 이동 + 공항 대기 + 비행), 울릉도는 배(항구까지 이동 + 승선 대기 + 운항)로 찾는다.
 * 찾지 못하면 추정하지 않고 출발지를 다시 적거나 비우라고 안내한다
 * (일정 화면은 편도 시간을 받지 않는다). 좌표는 조회에만 쓰고 저장하지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class HomeTripService {
    private final KakaoMapClient maps;
    private final BusGuideService buses;
    private final FlightGuideService flights;
    private final FerryGuideService ferries;
    private final TrainGuideService trains;

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
        return resolve(request, places, transport, null);
    }

    /** tripDate는 여행 첫날(미정이면 null)이며 기차 시간표를 고를 때만 쓴다. */
    public HomeTrip resolve(Request request, PlaceCandidateView places, KakaoMapClient.Transport transport, LocalDate tripDate) {
        if (request == null || request.departAt() == null || request.arriveBy() == null)
            throw failure("HOME_REQUIRED", "첫날 집에서 출발할 시각과 마지막 날 집에 도착할 시각을 적어 주세요.");
        checkMinutes(request.minutes(), "여행지까지");
        checkMinutes(request.returnMinutes(), "돌아갈 곳까지");
        String destination = places.destination().name();
        var targets = centers(places);
        var target = targets.isEmpty() ? null : targets.getFirst();

        // 가는 길: 출발지(적은 이름 또는 현재 위치) → 여행지
        Leg out;
        KakaoMapClient.Origin origin = null;
        if (!request.located() && !request.typed()) {
            if (request.minutes() == null) throw failure("HOME_ORIGIN", "출발지(집)를 적거나 '내 위치로 찾기'를 눌러 주세요.");
            out = new Leg("집", request.minutes(), "직접 입력한 편도 시간", null);
        } else {
            origin = request.located() ? maps.originAt(request.longitude(), request.latitude()) : maps.findOrigin(request.origin());
            out = leg(origin, request.minutes(), target, targets, destination, transport, true, tripDate);
        }

        // 오는 길: 돌아갈 곳을 따로 적었으면 그곳까지 따로 찾고, 아니면 출발지로 돌아온다(편도 시간만 따로 적었으면 그 값).
        // 같은 곳으로 돌아와도 가는 길과 오는 길의 소요시간이 다를 수 있어, 가는 길을 자동으로 찾았으면 여행지 → 출발지 방향도 따로 찾는다.
        Leg back;
        if (request.separateReturn()) {
            back = leg(maps.findOrigin(request.returnPlace()), request.returnMinutes(), target, targets, destination, transport, false, null);
        } else if (request.returnMinutes() != null) {
            back = new Leg(out.place(), request.returnMinutes(), "직접 입력한 편도 시간", null);
        } else if (origin != null && request.minutes() == null) {
            back = leg(origin, null, target, targets, destination, transport, false, null);
        } else {
            back = out;
        }
        return new HomeTrip(out.place(), request.departAt(), request.arriveBy(), out.minutes(), out.basis(), out.checkedAt(),
            back.place(), back.minutes(), back.basis());
    }

    /**
     * 집(place)과 여행지 사이 편도 시간. 직접 적은 시간이 있으면 그 값을, 없으면 길찾기 → 버스·기차 순서로 찾는다.
     * 오는 길(outbound=false)은 여행지 → 집 방향으로 길을 찾고, 버스는 운행표가 여행지행만 있어 같은 구간의 최단 소요를 쓴다.
     * 기차는 tripDate(가는 길만) 시간표를 먼저 보고, 오는 길은 오늘 시간표를 쓴다.
     */
    private Leg leg(KakaoMapClient.Origin home, Integer typed, Target target, java.util.List<Target> targets, String destination, KakaoMapClient.Transport transport, boolean outbound,
                    LocalDate tripDate) {
        if (typed != null) return new Leg(home.label(), typed, "직접 입력한 편도 시간", null);
        if (target == null)
            throw failure("HOME_MINUTES", destination + " 관광지 위치를 확인하지 못해 이동시간을 계산하지 못했어요. "
                + "출발지를 역이나 시·구 이름으로 다시 적거나, 출발지를 비우면 여행지 도착부터 일정을 짜요.");
        // 제주를 오가는 길은 비행기나 배를 타야 한다. 카카오 자동차 길찾기는 카페리 구간을 넣어 '성공'으로 답하므로 길찾기 대신
        // 비행기(가까운 공항까지 이동 + 공항 대기 + 오늘 운항편의 비행시간)로 찾는다. 제주 배편은 찾지 않는다.
        if (onJejuIsland(home.location()) != onJejuIsland(target.location())) {
            var flight = outbound ? flights.fastest(home.location(), target.location(), transport)
                : flights.fastest(target.location(), home.location(), transport);
            if (flight.isPresent() && flight.get().minutes() <= 600)
                return new Leg(home.label(), flight.get().minutes(), flight.get().basis(), flight.get().checkedAt());
            throw failure("HOME_MINUTES", (outbound ? home.label() + "에서 " + destination : destination + "에서 " + home.label())
                + "까지는 비행기나 배로 가야 하는데 오늘 운항하는 비행기 편이나 공항까지 가는 길을 찾지 못했어요. 출발지를 비우면 여행지 도착부터 일정을 짜요.");
        }
        // 울릉도는 배로만 갈 수 있다. 오늘 운항하는 여객선과 항구까지·항구에서의 길찾기를 더해 찾는다.
        if (FerryPorts.onUlleung(home.location()) != FerryPorts.onUlleung(target.location())) {
            var ferry = outbound ? ferries.fastest(home.location(), target.location(), transport)
                : ferries.fastest(target.location(), home.location(), transport);
            if (ferry.isPresent() && ferry.get().minutes() <= 600)
                return new Leg(home.label(), ferry.get().minutes(), ferry.get().basis(), ferry.get().checkedAt());
            throw failure("HOME_MINUTES", (outbound ? home.label() + "에서 " + destination : destination + "에서 " + home.label())
                + "까지는 배로 가야 하는데 오늘 운항하는 여객선이나 항구까지 가는 길을 찾지 못했어요. 출발지를 비우면 여행지 도착부터 일정을 짜요.");
        }
        // 여행지 관광지들의 가운데까지 조회한다. 첫 관광지까지와는 조금 다를 수 있다고 일정에 적는다.
        // 가운데 관광지가 차로 갈 수 없는 곳(산 위 호수 등, 카카오 '경로 없음')이면 다음으로 가까운 관광지까지 찾아 본다(2026-11-10).
        // 예전에는 가평의 가운데가 호명호수라 어느 출발지를 적어도 자동차 경로를 찾지 못했다.
        Leg kakao = null;
        for (var place : targets) {
            try {
                var time = outbound ? maps.travelTime(home.location(), place.location(), transport)
                    : maps.travelTime(place.location(), home.location(), transport);
                if (time.minutes() <= 600) kakao = new Leg(home.label(), time.minutes(),
                    (transport == KakaoMapClient.Transport.CAR ? "자동차" : "대중교통") + " · 카카오 길찾기, " + place.name() + " 부근 기준", time.checkedAt());
                break;
            } catch (RecommendationException error) {
                if ("MAP_AUTH".equals(error.getCode())) throw error;
                // 대중교통은 장거리면 어느 관광지든 경로가 없으므로 다시 묻지 않고 아래에서 버스·기차를 본다.
                if (transport != KakaoMapClient.Transport.CAR || !"MAP_NO_ROUTE".equals(error.getCode())) break;
            }
        }
        // 자동차이거나, 대중교통이라도 카카오 경로가 길지 않으면 그대로 쓴다.
        if (kakao != null && (transport == KakaoMapClient.Transport.CAR || kakao.minutes() < DIRECT_CHECK_MINUTES)) return kakao;
        // 카카오가 대중교통 경로를 찾지 못했거나 2시간 이상 걸린다고 한 구간은 직행 버스와 기차도 찾아 가장 빠른 쪽을 쓴다.
        // 카카오는 직행편이 있어도 다른 도시를 거쳐 갈아타는 경로를 줄 때가 있다(전주 → 신갈 → 대전 약 280분, 2026-11-10 확인).
        // 버스 시간에는 집 ↔ 터미널 이동이 빠져 있고, 기차 시간에는 집 ↔ 역 이동이 들어 있다(근거에 적는다).
        if (transport == KakaoMapClient.Transport.PUBLIC_TRANSIT) {
            Leg best = kakao;
            var train = outbound ? trains.fastest(home.location(), target.location(), tripDate)
                : trains.fastest(target.location(), home.location(), null);
            if (train.isPresent() && train.get().minutes() <= 600 && (best == null || train.get().minutes() < best.minutes()))
                best = new Leg(home.label(), train.get().minutes(), train.get().basis(), train.get().checkedAt());
            var bus = home.city() == null ? java.util.OptionalInt.empty() : buses.fastestMinutes(home.city(), destination);
            if (bus.isPresent() && (best == null || bus.getAsInt() < best.minutes()))
                best = new Leg(home.label(), bus.getAsInt(),
                    "직행 시외·고속버스 최단 소요" + (outbound ? "" : "(여행지행 기준)") + ", 집 ↔ 터미널 이동 제외", Instant.now());
            if (best != null) return best;
        }
        throw failure("HOME_MINUTES", (outbound ? home.label() + "에서 " + destination : destination + "에서 " + home.label()) + "까지 "
            + (transport == KakaoMapClient.Transport.CAR ? "자동차 경로" : "대중교통·직행 버스·기차") + "를 찾지 못했어요. 출발지를 역이나 시·구 이름으로 다시 적거나, 출발지를 비우면 여행지 도착부터 일정을 짜요.");
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
    /** 카카오 대중교통 길찾기가 이 시간(분) 이상이면 직행 버스·기차도 찾아 비교한다. */
    static final int DIRECT_CHECK_MINUTES = 120;
    /** 집 이동의 도착점으로 시도할 관광지 수. 가운데 관광지까지 자동차 경로가 없을 때만 다음 곳을 본다. */
    static final int TARGET_TRIES = 3;

    /** 관광지 후보 가운데와 가까운 관광지를 가까운 순서로 TARGET_TRIES곳까지. 좌표가 있는 후보가 없으면 비어 있다. */
    private static java.util.List<Target> centers(PlaceCandidateView places) {
        return TripConstraints.nearCenterFirst(places.attractions()).stream()
            .filter(p -> p.longitude() != null && p.latitude() != null).limit(TARGET_TRIES)
            .map(p -> new Target(p.name(), new KakaoMapClient.Location(p.address(), p.longitude(), p.latitude(), Instant.now())))
            .toList();
    }

    private static RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
