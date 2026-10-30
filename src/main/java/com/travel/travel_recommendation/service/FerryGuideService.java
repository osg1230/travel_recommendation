package com.travel.travel_recommendation.service;

import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 배로 가는 이동(울릉도). 집 출발·귀가 시간 계산에 쓰는 편도 시간을 만든다. 운항편은 국토교통부 TAGO 국내선박운항정보에서,
 * 집↔항구·항구↔관광지 이동은 카카오 길찾기에서 찾는다. 카카오 길찾기는 울릉도까지 가는 길을 바다를 건너 찾지 못하기 때문이다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class FerryGuideService {
    /** 출항 전 매표·승선에 잡는 시간과 내려서 터미널을 나오는 시간(분). */
    static final int BOARDING_WAIT = 30, LANDING_WAIT = 10;
    private final FerryClient ferries;
    private final KakaoMapClient maps;

    /** 배 편도 이동: 전체 분, 근거, 조회 시각. */
    public record Leg(int minutes, String basis, Instant checkedAt) {}

    /**
     * from에서 to까지 배로 가는 편도 시간. from 쪽 항구마다 오늘 운항편 가운데 to 쪽 항구로 가는 가장 짧은 편을 찾고,
     * 항구까지·항구에서의 이동(카카오 길찾기, transport)과 승선 대기 30분·하선 10분을 더해 전체 시간이 가장 짧은 조합을 쓴다.
     * 가까운 항구만 보지 않는 것은 항구마다 배의 소요시간이 크게 달라서다(오늘 운항 기준 포항 2시간 20분, 묵호 약 8시간).
     * 운항편은 오늘 기준이라 여행 당일·기상에 따라 다를 수 있다고 근거에 적는다. 운항편이나 길을 찾지 못하면 비어 있다.
     */
    public Optional<Leg> fastest(KakaoMapClient.Location from, KakaoMapClient.Location to, KakaoMapClient.Transport transport) {
        if (!ferries.configured()) return Optional.empty();
        boolean fromIsland = FerryPorts.onUlleung(from);
        var departures = fromIsland ? FerryPorts.ULLEUNG : FerryPorts.MAINLAND;
        var arrivals = fromIsland ? FerryPorts.MAINLAND : FerryPorts.ULLEUNG;
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        Leg best = null;
        for (var departure : departures) {
            List<FerryClient.Sailing> sailings;
            try { sailings = ferries.sailings(departure.id(), today); }
            catch (RecommendationException error) {
                // 활용신청이 안 된 키면 다른 항구도 모두 실패하므로 바로 그만둔다.
                if ("FERRY_AUTH".equals(error.getCode())) return Optional.empty();
                continue;
            }
            Integer toPort = null;
            for (var arrival : arrivals) {
                var sailing = sailings.stream().filter(s -> s.arrivalPort().equals(arrival.name()))
                    .min(Comparator.comparingInt(FerryClient.Sailing::minutes));
                if (sailing.isEmpty()) continue;
                try {
                    // 같은 출발 항구까지의 길은 한 번만 찾는다.
                    if (toPort == null) toPort = maps.travelTime(from, departure.location(), transport).minutes();
                    int fromPort = maps.travelTime(arrival.location(), to, transport).minutes();
                    int ship = sailing.get().minutes();
                    int total = toPort + BOARDING_WAIT + ship + LANDING_WAIT + fromPort;
                    if (best == null || total < best.minutes())
                        best = new Leg(total, "배 · " + departure.label() + " → " + arrival.label() + " 약 " + ship + "분(오늘 운항 기준, 기상에 따라 결항될 수 있음)"
                            + " + 승선 대기 " + (BOARDING_WAIT + LANDING_WAIT) + "분 + 항구까지·항구에서 "
                            + (transport == KakaoMapClient.Transport.CAR ? "자동차" : "대중교통") + " 이동(카카오 길찾기)", Instant.now());
                } catch (RecommendationException error) {
                    if ("MAP_AUTH".equals(error.getCode())) throw error;
                    // 이 항구까지의 길을 찾지 못하면 다음 항구를 본다.
                    break;
                }
            }
        }
        return Optional.ofNullable(best);
    }
}
