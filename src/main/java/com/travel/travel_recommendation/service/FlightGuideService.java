package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.FlightGuide;
import com.travel.travel_recommendation.dto.PlaceCandidateView;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 비행기로 가는 이동(제주처럼 바다를 건너는 여행). 집 출발·귀가 시간 계산에 쓰는 편도 시간과, 일정 초안 화면에서 사용자가 누를 때만 조회하는
 * 비행기 안내를 만든다. 운항편은 국토교통부 TAGO 국내항공운항정보에서, 집↔공항·공항↔관광지 이동은 카카오 길찾기에서 찾는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class FlightGuideService {
    /** 출발 공항에서 체크인·보안검색·탑승까지 잡는 시간과 도착 공항에서 내려 나오는 시간(분). */
    static final int DEPARTURE_WAIT = 60, ARRIVAL_WAIT = 20;
    /** 집에서 가까운 공항 몇 곳, 여행지에서 가까운 공항 몇 곳을 조합해 본다. */
    static final int ORIGIN_AIRPORTS = 3, TARGET_AIRPORTS = 2;
    private final ItineraryJobService itineraries;
    private final FlightClient flights;
    private final KakaoMapClient maps;

    /** 비행기 편도 이동: 전체 분, 근거, 조회 시각. */
    public record Leg(int minutes, String basis, Instant checkedAt) {}

    /**
     * from에서 to까지 비행기로 가는 편도 시간. from에서 가까운 공항 3곳과 to에서 가까운 공항 2곳 가운데 오늘 운항편이 있는 첫 조합을 쓰고,
     * 공항까지·공항에서의 이동은 카카오 길찾기(transport)로, 공항 대기는 출발 60분·도착 20분으로 더한다.
     * 운항편은 오늘 기준이라 여행 당일과 다를 수 있다고 근거에 적는다. 운항편이나 길을 찾지 못하면 비어 있다.
     */
    public Optional<Leg> fastest(KakaoMapClient.Location from, KakaoMapClient.Location to, KakaoMapClient.Transport transport) {
        if (!flights.configured()) return Optional.empty();
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        for (var departure : Airports.nearest(from, ORIGIN_AIRPORTS)) {
            for (var arrival : Airports.nearest(to, TARGET_AIRPORTS)) {
                if (departure.id().equals(arrival.id())) continue;
                List<FlightClient.Flight> found;
                try { found = flights.flights(departure.id(), arrival.id(), today); }
                catch (RecommendationException error) {
                    // 활용신청이 안 된 키면 다른 조합도 모두 실패하므로 바로 그만둔다.
                    if ("FLIGHT_AUTH".equals(error.getCode())) return Optional.empty();
                    continue;
                }
                if (found.isEmpty()) continue;
                int flight = found.stream().mapToInt(FlightClient.Flight::minutes).min().orElseThrow();
                try {
                    int toAirport = maps.travelTime(from, departure.location(), transport).minutes();
                    int fromAirport = maps.travelTime(arrival.location(), to, transport).minutes();
                    int total = toAirport + DEPARTURE_WAIT + flight + ARRIVAL_WAIT + fromAirport;
                    return Optional.of(new Leg(total, "비행기 · " + departure.name() + " → " + arrival.name() + " 약 " + ReadableText.duration(flight) + "(오늘 운항 기준)"
                        + " + 공항 대기 " + (DEPARTURE_WAIT + ARRIVAL_WAIT) + "분 + 공항까지·공항에서 "
                        + (transport == KakaoMapClient.Transport.CAR ? "자동차" : "대중교통") + " 이동(카카오 길찾기)", Instant.now()));
                } catch (RecommendationException error) {
                    if ("MAP_AUTH".equals(error.getCode())) throw error;
                    // 이 공항까지의 길을 찾지 못하면 다음 조합을 본다.
                }
            }
        }
        return Optional.empty();
    }

    /**
     * 일정 초안 화면의 비행기 안내. 사용자가 고른 출발 공항에서 여행지(관광지 가운데)에 가까운 공항으로 가는 운항편을 항공사별로 요약한다.
     * 여행 날짜의 운항편이 있으면 그 날짜, 없으면(먼 날짜는 등록 전) 오늘 기준으로 보여 주고 그 사실을 적는다.
     */
    public FlightGuide guide(UUID jobId, String login, byte[] guestHash, String fromAirportId) {
        // 본인(또는 같은 비회원 세션)의 완성된 일정에서만 조회한다.
        var view = itineraries.view(jobId, login, guestHash);
        if (view.result() == null) throw new RecommendationException("FLIGHT_NO_PLAN", "일정 초안이 완성된 뒤에 비행기 정보를 찾을 수 있어요.");
        var departure = Airports.byId(fromAirportId == null ? "" : fromAirportId)
            .orElseThrow(() -> new RecommendationException("FLIGHT_INPUT", "출발 공항을 목록에서 골라 주세요."));
        var target = center(view.input().places());
        if (target == null) throw new RecommendationException("FLIGHT_NO_TARGET", "여행지 관광지 위치를 확인하지 못해 도착 공항을 정하지 못했어요.");
        var arrivals = Airports.nearest(target, TARGET_AIRPORTS).stream().filter(a -> !a.id().equals(departure.id())).toList();
        if (arrivals.isEmpty()) throw new RecommendationException("FLIGHT_INPUT", "출발 공항이 여행지 공항과 같아요. 다른 공항을 골라 주세요.");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate tripDate = view.input().conditions().startDate();
        List<LocalDate> dates = tripDate != null && tripDate.isAfter(today) ? List.of(tripDate, today) : List.of(today);
        for (var arrival : arrivals) {
            for (var date : dates) {
                var found = flights.flights(departure.id(), arrival.id(), date);
                if (found.isEmpty()) continue;
                return new FlightGuide(departure.name(), arrival.name(), date, tripDate, routes(found), null);
            }
        }
        return new FlightGuide(departure.name(), arrivals.getFirst().name(), today, tripDate, List.of(),
            "오늘과 여행 날짜 모두 " + departure.name() + "에서 " + arrivals.getFirst().name() + "으로 가는 운항편 정보를 찾지 못했어요. 다른 출발 공항을 고르거나 항공사·예매처에서 확인해 주세요.");
    }

    /** 운항편을 항공사별로 묶어 편수·첫 편·막 편·가장 짧은 비행시간·최저 일반석 요금을 낸다. 가장 빠른 항공사부터 보여 준다. */
    static List<FlightGuide.Route> routes(List<FlightClient.Flight> found) {
        Map<String, List<FlightClient.Flight>> byAirline = new LinkedHashMap<>();
        for (var flight : found) byAirline.computeIfAbsent(flight.airline().isBlank() ? "항공사 미표기" : flight.airline(), k -> new ArrayList<>()).add(flight);
        List<FlightGuide.Route> routes = new ArrayList<>();
        for (var entry : byAirline.entrySet()) {
            var list = entry.getValue().stream().sorted(Comparator.comparing(FlightClient.Flight::departure)).toList();
            routes.add(new FlightGuide.Route(entry.getKey(), list.size(), clock(list.getFirst().departure()), clock(list.getLast().departure()),
                list.stream().mapToInt(FlightClient.Flight::minutes).min().orElseThrow(),
                list.stream().map(FlightClient.Flight::economyCharge).filter(Objects::nonNull).min(Integer::compare).orElse(null)));
        }
        routes.sort(Comparator.comparingInt(FlightGuide.Route::fastestMinutes));
        return List.copyOf(routes);
    }

    private static String clock(LocalDateTime time) { return String.format("%02d:%02d", time.getHour(), time.getMinute()); }

    /** 관광지 후보 가운데와 가장 가까운 관광지의 위치. 좌표가 있는 후보가 없으면 null이다. */
    private static KakaoMapClient.Location center(PlaceCandidateView places) {
        return TripConstraints.nearCenterFirst(places.attractions()).stream()
            .filter(p -> p.longitude() != null && p.latitude() != null).findFirst()
            .map(p -> new KakaoMapClient.Location(p.address(), p.longitude(), p.latitude(), Instant.now()))
            .orElse(null);
    }
}
