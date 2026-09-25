package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.HomeTrip;
import com.travel.travel_recommendation.dto.PlaceCandidateView;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 첫날 집 출발·마지막 날 귀가 설정(2026-09-24 결정)을 만든다. 집에서 여행지까지 편도 이동시간은
 * 사용자가 직접 적은 값이 먼저이고, 없으면 자동차는 카카오 길찾기, 대중교통은 카카오 대중교통 길찾기 → 직행 시외·고속버스 순서로 찾는다.
 * 찾지 못하면 추정하지 않고 직접 적어 달라고 한다. 출발지 좌표는 조회에만 쓰고 저장하지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class HomeTripService {
    private final KakaoMapClient maps;
    private final BusGuideService buses;

    /** 일정 입력 화면에서 받은 값. 좌표는 '내 위치로 찾기'를 눌렀을 때만 오고, minutes는 직접 적은 편도 이동시간(분)이다. */
    public record Request(String origin, Double longitude, Double latitude, LocalTime departAt, LocalTime arriveBy, Integer minutes) {
        public boolean located() { return longitude != null && latitude != null; }
        public boolean typed() { return origin != null && !origin.isBlank(); }
    }

    public HomeTrip resolve(Request request, PlaceCandidateView places, KakaoMapClient.Transport transport) {
        if (request == null || request.departAt() == null || request.arriveBy() == null)
            throw failure("HOME_REQUIRED", "첫날 집에서 출발할 시각과 마지막 날 집에 도착할 시각을 적어 주세요.");
        Integer manual = request.minutes();
        if (manual != null && (manual < 10 || manual > 600))
            throw failure("HOME_MINUTES", "여행지까지 편도 이동시간은 10~600분으로 적어 주세요.");
        if (!request.located() && !request.typed()) {
            if (manual == null) throw failure("HOME_ORIGIN", "출발지(집)를 적거나 '내 위치로 찾기'를 눌러 주세요.");
            return new HomeTrip("집", request.departAt(), request.arriveBy(), manual, "직접 입력한 편도 시간", null);
        }
        var origin = request.located() ? maps.originAt(request.longitude(), request.latitude()) : maps.findOrigin(request.origin());
        if (manual != null) return new HomeTrip(origin.label(), request.departAt(), request.arriveBy(), manual, "직접 입력한 편도 시간", null);

        String destination = places.destination().name();
        var target = center(places);
        if (target == null)
            throw failure("HOME_MINUTES", destination + " 관광지 위치를 확인하지 못해 이동시간을 계산하지 못했어요. 편도 이동시간을 직접 적어 주세요.");
        // 여행지 관광지들의 가운데까지 조회한다. 첫 관광지까지와는 조금 다를 수 있다고 일정에 적는다.
        try {
            var time = maps.travelTime(origin.location(), target.location(), transport);
            if (time.minutes() <= 600) return new HomeTrip(origin.label(), request.departAt(), request.arriveBy(), time.minutes(),
                (transport == KakaoMapClient.Transport.CAR ? "자동차" : "대중교통") + " · 카카오 길찾기, " + target.name() + " 부근까지", time.checkedAt());
        } catch (RecommendationException error) {
            if ("MAP_AUTH".equals(error.getCode())) throw error;
        }
        if (transport == KakaoMapClient.Transport.PUBLIC_TRANSIT && origin.city() != null) {
            var bus = buses.fastestMinutes(origin.city(), destination);
            if (bus.isPresent()) return new HomeTrip(origin.label(), request.departAt(), request.arriveBy(), bus.getAsInt(),
                "직행 시외·고속버스 최단 소요, 집 ↔ 터미널 이동 제외", Instant.now());
        }
        throw failure("HOME_MINUTES", origin.label() + "에서 " + destination + "까지 "
            + (transport == KakaoMapClient.Transport.CAR ? "자동차 경로" : "대중교통·직행 버스") + "를 찾지 못했어요. 편도 이동시간을 직접 적어 주세요.");
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
