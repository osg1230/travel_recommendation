package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidateView;
import com.travel.travel_recommendation.dto.TrainGuide;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 기차로 가는 이동(2026-11-03 열차 이동시간 계획). 집 출발·귀가 시간 계산에 쓰는 편도 시간과, 일정 초안 화면에서 사용자가 누를 때만 조회하는
 * 기차 안내를 만든다. 열차 시간표는 국토교통부 TAGO 열차정보에서, 집↔역·역↔관광지 이동은 카카오 대중교통 길찾기에서 찾는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class TrainGuideService {
    /** 역에 도착해 열차를 타기까지 잡는 시간(분). 표를 확인하고 승강장까지 가는 시간이다. */
    static final int BOARDING_WAIT = 15;
    /** 집에서 가까운 역 몇 곳, 여행지에서 가까운 역 몇 곳을 조합해 본다. */
    static final int ORIGIN_STATIONS = 2, TARGET_STATIONS = 2;
    private final ItineraryJobService itineraries;
    private final TrainClient trains;
    private final KakaoMapClient maps;

    /** 기차 편도 이동: 전체 분, 근거, 조회 시각. */
    public record Leg(int minutes, String basis, Instant checkedAt) {}

    /**
     * from에서 to까지 기차로 가는 편도 시간. from에서 가까운 역 2곳과 to에서 가까운 역 2곳 가운데 열차가 있는 첫 조합을 쓴다.
     * 여행 날짜 시간표를 먼저 보고, 비어 있으면(먼 날짜는 등록 전) 오늘 시간표의 가장 짧은 소요시간을 쓰며 근거에 그 사실을 적는다.
     * 전체 시간은 집→역 이동 + 승차 대기 15분 + 열차 + 역→관광지 이동이다. 열차나 역까지의 길을 찾지 못하면 비어 있다.
     */
    public Optional<Leg> fastest(KakaoMapClient.Location from, KakaoMapClient.Location to, LocalDate tripDate) {
        if (!trains.configured()) return Optional.empty();
        for (var departure : TrainStations.nearest(from, ORIGIN_STATIONS)) {
            for (var arrival : TrainStations.nearest(to, TARGET_STATIONS)) {
                if (departure.id().equals(arrival.id())) continue;
                Timetable timetable;
                try { timetable = timetable(departure.id(), arrival.id(), tripDate); }
                catch (RecommendationException error) {
                    // 활용신청이 안 된 키면 다른 조합도 모두 실패하므로 바로 그만둔다.
                    if ("TRAIN_AUTH".equals(error.getCode())) return Optional.empty();
                    continue;
                }
                if (timetable == null) continue;
                int train = timetable.trains().stream().mapToInt(TrainClient.Train::minutes).min().orElseThrow();
                try {
                    int toStation = maps.travelTime(from, departure.location(), KakaoMapClient.Transport.PUBLIC_TRANSIT).minutes();
                    int fromStation = maps.travelTime(arrival.location(), to, KakaoMapClient.Transport.PUBLIC_TRANSIT).minutes();
                    int total = toStation + BOARDING_WAIT + train + fromStation;
                    return Optional.of(new Leg(total, "기차 · " + departure.name() + "역 → " + arrival.name() + "역 약 " + ReadableText.duration(train) + "("
                        + (timetable.today() ? "오늘 운행 기준" : "여행 날짜 운행 기준") + ") + 승차 대기 " + BOARDING_WAIT
                        + "분 + 역까지·역에서 대중교통 이동(카카오 길찾기)", Instant.now()));
                } catch (RecommendationException error) {
                    if ("MAP_AUTH".equals(error.getCode())) throw error;
                    // 이 역까지의 길을 찾지 못하면 다음 조합을 본다.
                }
            }
        }
        return Optional.empty();
    }

    /** 찾은 시간표. today는 여행 날짜가 아니라 오늘 시간표를 썼다는 뜻이다. */
    private record Timetable(List<TrainClient.Train> trains, LocalDate date, boolean today) {}

    /** 여행 날짜(오늘 이후일 때) → 오늘 순서로 열차를 찾는다. 둘 다 없으면 null이다. */
    private Timetable timetable(String departureId, String arrivalId, LocalDate tripDate) {
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        List<LocalDate> dates = tripDate != null && tripDate.isAfter(today) ? List.of(tripDate, today) : List.of(today);
        for (var date : dates) {
            var found = trains.trains(departureId, arrivalId, date);
            if (!found.isEmpty()) return new Timetable(found, date, date.equals(today));
        }
        return null;
    }

    /**
     * 일정 초안 화면의 기차 안내. 버스 안내와 같은 입력(도착 지역, 출발 도시·현재 위치)을 받아, 출발지와 도착 지역 근처의 역 사이
     * 열차를 종류별로 요약한다. 열차가 다니는 역 조합이 여럿이면 가장 빨리 갈 수 있는 조합을 고른다.
     * 출발지를 비우면 주변 지역으로 갈 때만 지금 여행지에서 출발한다.
     */
    public TrainGuide guide(UUID jobId, String login, byte[] guestHash, String to, String from, Double longitude, Double latitude) {
        // 본인(또는 같은 비회원 세션)의 완성된 일정에서만 조회한다.
        var view = itineraries.view(jobId, login, guestHash);
        if (view.result() == null) throw new RecommendationException("TRAIN_NO_PLAN", "일정 초안이 완성된 뒤에 기차 정보를 찾을 수 있어요.");
        String destination = view.input().places().destination().name();
        // 도착지는 이 일정의 여행지나 화면에 추천된 주변 지역만 고를 수 있다.
        Set<String> allowed = new LinkedHashSet<>(List.of(destination));
        if (view.result().nearby() != null) view.result().nearby().regions().forEach(r -> allowed.add(r.name()));
        if (to == null || !allowed.contains(to)) throw new RecommendationException("TRAIN_INPUT", "도착 지역을 목록에서 다시 골라 주세요.");
        boolean noOrigin = (longitude == null || latitude == null) && (from == null || from.isBlank());
        if (noOrigin && to.equals(destination))
            throw new RecommendationException("TRAIN_ORIGIN", "출발 도시나 역 이름을 적거나 '내 위치로 찾기'를 눌러 주세요.");
        var destinationCenter = center(view.input().places());
        var target = to.equals(destination) ? destinationCenter : terminal(to);
        if (target == null) throw new RecommendationException("TRAIN_NO_TARGET", to + " 위치를 확인하지 못해 도착역을 정하지 못했어요.");

        // 출발지 정하기: 현재 위치 → 입력한 도시·역 → (주변 지역으로 갈 때) 지금 여행지 순서다. 버스 안내와 같다.
        KakaoMapClient.Location origin;
        String label;
        if (longitude != null && latitude != null) {
            var found = maps.originAt(longitude, latitude); origin = found.location(); label = found.label();
        } else if (from != null && !from.isBlank()) {
            origin = maps.findOrigin(from).location(); label = from.strip();
        } else if (!to.equals(destination) && destinationCenter != null) {
            origin = destinationCenter; label = destination + "(지금 여행지)";
        } else throw new RecommendationException("TRAIN_ORIGIN", "출발 도시나 역 이름을 적거나 '내 위치로 찾기'를 눌러 주세요.");

        LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate tripDate = view.input().conditions().startDate();
        // 가장 가까운 역만 보면 부산 광안리 여행에 센텀역(무궁화호 2시간 19분)이 나오고 부산역 KTX가 빠진다(2026-11-28).
        // 그래서 도착 지역 근처의 역을 더 보고, '열차 시간 + 역까지 가는 시간(직선거리 어림)'이 가장 짧은 역 조합을 고른다.
        var arrivals = guideArrivals(target);
        TrainGuide best = null; int bestMinutes = Integer.MAX_VALUE;
        for (var departure : TrainStations.nearest(origin, ORIGIN_STATIONS)) {
            for (var arrival : arrivals) {
                if (departure.id().equals(arrival.id())) continue;
                var timetable = timetable(departure.id(), arrival.id(), tripDate);
                if (timetable == null) continue;
                int train = timetable.trains().stream().mapToInt(TrainClient.Train::minutes).min().orElseThrow();
                int total = train + accessMinutes(origin, departure) + accessMinutes(target, arrival);
                if (total < bestMinutes) {
                    bestMinutes = total;
                    best = new TrainGuide(label, to, departure.name() + "역", arrival.name() + "역", timetable.date(), tripDate,
                        routes(timetable.trains()), null);
                }
            }
        }
        if (best != null) return best;
        var nearestFrom = TrainStations.nearest(origin, 1).getFirst();
        if (nearestFrom.id().equals(arrivals.getFirst().id()))
            return new TrainGuide(label, to, nearestFrom.name() + "역", arrivals.getFirst().name() + "역", today, tripDate, List.of(),
                "출발지와 도착 지역이 같은 역에서 가까워 기차로 갈 필요가 없어요.");
        return new TrainGuide(label, to, nearestFrom.name() + "역", arrivals.getFirst().name() + "역", today, tripDate, List.of(),
            "오늘과 여행 날짜 모두 가까운 역 사이에 바로 가는 열차를 찾지 못했어요. 갈아타는 열차는 안내하지 않으니 코레일 예매처에서 확인해 주세요.");
    }

    /** 기차 안내에서 도착 지역 근처로 보는 역의 수와 거리(km). 가까운 2곳은 거리와 상관없이 본다. */
    static final int GUIDE_ARRIVAL_STATIONS = 4;
    static final double GUIDE_ARRIVAL_KM = 20;
    /** 역까지 가는 시간을 직선거리로 어림할 때 1km에 잡는 분(시내 대중교통 시속 약 20km). 역을 고르는 데만 쓰고 화면에는 보여 주지 않는다. */
    static final int ACCESS_MINUTES_PER_KM = 3;

    /** 도착 지역에서 가까운 역 2곳과, 그다음으로 가까운 역 가운데 20km 안에 있는 곳(모두 4곳까지). */
    static List<TrainStations.Station> guideArrivals(KakaoMapClient.Location target) {
        var near = TrainStations.nearest(target, GUIDE_ARRIVAL_STATIONS);
        List<TrainStations.Station> stations = new ArrayList<>();
        for (var station : near)
            if (stations.size() < TARGET_STATIONS || kilometers(target, station) <= GUIDE_ARRIVAL_KM) stations.add(station);
        return stations;
    }

    private static double kilometers(KakaoMapClient.Location place, TrainStations.Station station) {
        return Airports.kilometers(place.longitude(), place.latitude(), station.longitude(), station.latitude());
    }

    private static int accessMinutes(KakaoMapClient.Location place, TrainStations.Station station) {
        return (int) Math.round(kilometers(place, station) * ACCESS_MINUTES_PER_KM);
    }

    /** 열차를 종류별로 묶어 횟수·첫차·막차·가장 짧은 소요·최저 어른 요금을 낸다. 가장 빠른 종류부터 보여 준다. */
    static List<TrainGuide.Route> routes(List<TrainClient.Train> found) {
        Map<String, List<TrainClient.Train>> byGrade = new LinkedHashMap<>();
        for (var train : found) byGrade.computeIfAbsent(train.grade().isBlank() ? "종류 미표기" : train.grade(), k -> new ArrayList<>()).add(train);
        List<TrainGuide.Route> routes = new ArrayList<>();
        for (var entry : byGrade.entrySet()) {
            var list = entry.getValue().stream().sorted(Comparator.comparing(TrainClient.Train::departure)).toList();
            routes.add(new TrainGuide.Route(entry.getKey(), list.size(), clock(list.getFirst().departure()), clock(list.getLast().departure()),
                list.stream().mapToInt(TrainClient.Train::minutes).min().orElseThrow(),
                list.stream().map(TrainClient.Train::adultCharge).filter(Objects::nonNull).min(Integer::compare).orElse(null)));
        }
        routes.sort(Comparator.comparingInt(TrainGuide.Route::fastestMinutes));
        return List.copyOf(routes);
    }

    private static String clock(LocalDateTime time) { return String.format("%02d:%02d", time.getHour(), time.getMinute()); }

    /** 주변 지역은 이동시간 기준 터미널 위치를 그 지역의 위치로 쓴다. */
    private static KakaoMapClient.Location terminal(String region) {
        var terminal = NearbyRegionService.TERMINALS.get(region);
        return terminal == null ? null : new KakaoMapClient.Location(terminal.address(), terminal.longitude(), terminal.latitude(), Instant.now());
    }

    /** 관광지 후보 가운데와 가장 가까운 관광지의 위치. 좌표가 있는 후보가 없으면 null이다. */
    private static KakaoMapClient.Location center(PlaceCandidateView places) {
        return TripConstraints.nearCenterFirst(places.attractions()).stream()
            .filter(p -> p.longitude() != null && p.latitude() != null).findFirst()
            .map(p -> new KakaoMapClient.Location(p.address(), p.longitude(), p.latitude(), Instant.now()))
            .orElse(null);
    }
}
