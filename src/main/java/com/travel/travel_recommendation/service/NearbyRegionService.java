package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import com.travel.travel_recommendation.service.KakaoMapClient.Location;
import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 주변 지역 추천(SFR-07). 일정 초안이 완성된 뒤 참고용으로 붙이며 일정 자체는 바꾸지 않는다.
 * 1) 추천 여부 판단(07-01) → 2) 기준 시간 안의 지역 탐색(07-02) → 3) 지역과 관광지 추천(07-03) 순서로 처리한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class NearbyRegionService {
    /** 요구분석서의 "1~2시간 이내" 중 상한. 편도 기준이다. */
    static final int MAX_MINUTES=120;
    /** 사용자가 정하지 않았을 때 주변 지역을 권할 만큼 비는 시간. 편도 1시간 이동을 왕복하고 1시간 둘러보는 정도다. */
    static final int SPARE_MINUTES=180;
    private static final int MAX_REGIONS=3, MAX_ATTRACTIONS=3;
    /** 터미널 사이 직선거리 상한. 고속도로 평균 시속을 넉넉히 잡아도 2시간 안에 갈 수 없는 거리다. */
    static final double MAX_STRAIGHT_KM=150;

    /**
     * 지역마다 이동시간을 재는 기준 터미널. 2026-09-21 카카오 장소 검색으로 이름·주소·좌표를 확인했다.
     * busName은 TAGO 시외버스정보, expressName은 TAGO 고속버스정보의 터미널 이름이다(2026-09-21 터미널 목록 조회로 확인).
     * 부산종합버스터미널은 시외버스에 '부산동부', 고속버스에 '부산'으로 등록되어 있다(카카오에서도 같은 주소).
     */
    record Terminal(String name, String busName, String expressName, String address, double longitude, double latitude) {}
    static final Map<String,Terminal> TERMINALS=Map.of(
        "강릉",new Terminal("강릉시외버스터미널","강릉","강릉","강원특별자치도 강릉시 하슬라로 27",128.878876,37.754680),
        "속초",new Terminal("속초시외버스터미널","속초","속초","강원특별자치도 속초시 장안로 16",128.590814,38.211223),
        "양양",new Terminal("양양종합여객터미널","양양","양양","강원특별자치도 양양군 양양읍 동해대로 2700",128.629777,38.085294),
        "부산",new Terminal("부산종합버스터미널","부산동부","부산","부산광역시 금정구 중앙대로 2238",129.095472,35.284773),
        "경주",new Terminal("경주시외버스터미널","경주시외","경주","경상북도 경주시 강변로 184",129.202469,35.839800),
        "울산",new Terminal("울산시외버스터미널","울산","울산","울산광역시 남구 화합로 133",129.339729,35.536562));

    /** 주변 지역 추천을 제공하는 여행지인지. 추천·조건 화면에서 미리 알려 줄 때 쓴다. */
    public static boolean supports(String destinationName) { return TERMINALS.containsKey(destinationName); }
    /** 주변 지역 추천을 제공하는 여행지 이름(가나다순). */
    public static String supportedNames() { return String.join("·",TERMINALS.keySet().stream().sorted().toList()); }

    private final PlaceRepository places;
    private final KakaoMapClient maps;
    private final IntercityBusClient buses;

    public Nearby recommend(Input input,Result plan) {
        Instant checkedAt=Instant.now();
        String basis=input.transport()==Transport.PUBLIC_TRANSIT?"INTERCITY_BUS":"CAR";
        // 1) 추천 여부 판단: 사용자가 고른 값을 먼저 따르고, 정하지 않았으면 일정의 빈 시간으로 판단한다.
        Boolean wanted=input.conditions().includeNearby();
        int spare=spareMinutes(input,plan);
        if(Boolean.FALSE.equals(wanted))
            return new Nearby("NOT_REQUESTED","조건에서 '선택 지역 중심'을 골라 주변 지역은 찾지 않았어요.",basis,List.of(),null,checkedAt);
        if(wanted==null && spare<SPARE_MINUTES)
            return new Nearby("NO_SPARE_TIME","하루 일정에 "+SPARE_MINUTES/60+"시간 이상 빈 시간이 없어 주변 지역은 권하지 않았어요. 조건에서 '주변 지역 포함'을 고르면 찾아 드려요.",basis,List.of(),null,checkedAt);
        String reason=Boolean.TRUE.equals(wanted)?"조건에서 '주변 지역 포함'을 골라 주변 지역을 찾았어요."
            :"하루 일정에 약 "+spare/60+"시간 "+(spare%60>0?spare%60+"분 ":"")+"빈 시간이 있어 주변 지역을 찾았어요.";
        var origin=TERMINALS.get(input.places().destination().name());
        if(origin==null) return new Nearby("UNSUPPORTED","주변 지역 추천은 이동시간을 잴 기준 터미널을 확인한 여행지("+supportedNames()+")에서만 제공해요. 이 여행지는 아직 지원하지 않아요.",basis,List.of(),null,checkedAt);
        // 2) 탐색: 확인된 여행지 중 기준 시간 안에 닿는 곳만 남긴다.
        List<NearbyRegion> found=new ArrayList<>();
        // 시외버스 정보에 배차가 아예 없는 지역. 실제로 버스가 없다는 뜻이 아니라 판단하지 못한 곳이다.
        List<String> unchecked=new ArrayList<>();
        String notice=null;
        try {
            for(Place destination:places.findDestinations()) {
                var target=TERMINALS.get(destination.getName());
                if(target==null || target==origin || destination.getId().equals(input.places().destination().placeId())) continue;
                // 직선거리만으로도 2시간 안에 닿을 수 없는 곳은 외부 조회 없이 뺀다.
                if(straightKilometers(origin,target)>MAX_STRAIGHT_KM) continue;
                if("CAR".equals(basis)) {
                    var region=byCar(origin,target,destination);
                    if(region!=null) found.add(region);
                    continue;
                }
                var trips=busTrips(origin,target);
                if(trips.isEmpty()) { unchecked.add(destination.getName()); continue; }
                var region=byBus(origin,target,destination,trips);
                if(region!=null) found.add(region);
            }
        } catch(RecommendationException error) {
            // 주변 지역은 참고 기능이라 조회에 실패해도 일정 초안은 그대로 제공한다.
            return new Nearby("LOOKUP_FAILED",error.getMessage(),basis,List.of(),null,checkedAt);
        }
        if("INTERCITY_BUS".equals(basis)) {
            notice="시외버스 시간은 오늘 운행 기준이에요. 여행 날짜의 배차·요금은 다를 수 있으니 이용 전에 터미널이나 예매처에서 확인해 주세요.";
            if(!unchecked.isEmpty()) notice+=" 시외버스 정보에 오늘 직행 배차가 등록되지 않아 판단하지 못한 지역: "+String.join(", ",unchecked)+".";
        }
        found.sort(Comparator.comparingInt(NearbyRegion::minutes));
        if(found.isEmpty()) {
            String none="CAR".equals(basis)?"자동차로 "+MAX_MINUTES/60+"시간 안에 갈 수 있는 주변 지역이 없어요."
                :unchecked.isEmpty()?"오늘 운행하는 직행 시외버스로 "+MAX_MINUTES/60+"시간 안에 갈 수 있는 주변 지역이 없어요."
                :"시외버스 정보로 "+MAX_MINUTES/60+"시간 안에 갈 수 있다고 확인한 주변 지역이 없어요.";
            return new Nearby("NONE_FOUND",reason+" "+none,basis,List.of(),notice,checkedAt);
        }
        // 3) 추천: 가까운 지역부터 관광지와 함께 보여 준다.
        return new Nearby("RECOMMENDED",reason,basis,found.subList(0,Math.min(MAX_REGIONS,found.size())),notice,checkedAt);
    }

    /** 하루 종료 시간에서 그날 마지막 활동이 끝난 시각을 뺀 값 중 가장 큰 값(분). */
    static int spareMinutes(Input input,Result plan) {
        int best=0;
        for(var day:plan.days()) {
            LocalTime last=day.items().isEmpty()?input.start():day.items().getLast().end();
            if(last.isBefore(input.end())) best=Math.max(best,(int)Duration.between(last,input.end()).toMinutes());
        }
        return best;
    }

    private NearbyRegion byCar(Terminal origin,Terminal target,Place destination) {
        var time=maps.travelTime(location(origin),location(target),Transport.CAR);
        if(time.minutes()>MAX_MINUTES) return null;
        return region(destination,time.minutes(),origin.name()+" → "+target.name()+" (카카오 자동차 예상)",null);
    }

    /** 이 지역 하나만 판단하지 못하게 만드는 오류. 인증·연결 오류처럼 모든 지역에 같은 오류는 전체 실패로 둔다. */
    private static final Set<String> TARGET_ONLY_ERRORS=Set.of("BUS_TERMINAL_NOT_FOUND","BUS_TERMINAL_AMBIGUOUS","BUS_TOO_MANY");

    /** 오늘 직행 배차. 출발지 터미널을 정하지 못하면 전체 조회 실패로, 도착지 쪽 문제면 그 지역만 배차 없음(판단 못 함)으로 본다. */
    private List<IntercityBusClient.Trip> busTrips(Terminal origin,Terminal target) {
        String from=buses.terminalId(origin.busName());
        try { return buses.trips(from,buses.terminalId(target.busName()),LocalDate.now(ZoneId.of("Asia/Seoul"))); }
        catch(RecommendationException error) {
            if(TARGET_ONLY_ERRORS.contains(error.getCode())) return List.of();
            throw error;
        }
    }

    private NearbyRegion byBus(Terminal origin,Terminal target,Place destination,List<IntercityBusClient.Trip> trips) {
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        // 직행 배차 중 기준 시간 안에 도착하는 것만 센다. 환승 경로는 찾지 않는다.
        var fast=trips.stream().filter(t->t.minutes()<=MAX_MINUTES).toList();
        if(fast.isEmpty()) return null;
        int minutes=fast.stream().mapToInt(IntercityBusClient.Trip::minutes).min().orElseThrow();
        Integer charge=fast.stream().map(IntercityBusClient.Trip::charge).filter(Objects::nonNull).min(Integer::compare).orElse(null);
        var bus=new BusInfo(today,fast.size(),fast.getFirst().departure().toLocalTime(),fast.getLast().departure().toLocalTime(),charge);
        return region(destination,minutes,origin.name()+" → "+target.name()+" (직행 시외버스)",bus);
    }

    private NearbyRegion region(Place destination,int minutes,String route,BusInfo bus) {
        // 공식 출처로 확인해 넣은 관광지만 추천한다. 카카오 임시 후보는 주변 지역 추천에 쓰지 않는다.
        var attractions=places.findLocalPlaces(destination.getRegion()).stream()
            .filter(p->"ATTRACTION".equals(p.getPlaceType()) && "SYSTEM".equals(p.getSourceProvider()))
            .limit(MAX_ATTRACTIONS)
            .map(p->new NearbyAttraction(p.getId(),p.getName(),p.getSubtype(),p.getDescription(),p.getAddress(),p.getSourceUrl()))
            .toList();
        return new NearbyRegion(destination.getId(),destination.getName(),destination.getRegion(),destination.getDescription(),
            destination.getSourceUrl(),minutes,route,bus,attractions);
    }

    /** 위경도 두 점 사이의 직선거리(km, 하버사인 공식). */
    static double straightKilometers(Terminal a,Terminal b) {
        double lat1=Math.toRadians(a.latitude()), lat2=Math.toRadians(b.latitude());
        double dLat=lat2-lat1, dLon=Math.toRadians(b.longitude()-a.longitude());
        double h=Math.pow(Math.sin(dLat/2),2)+Math.cos(lat1)*Math.cos(lat2)*Math.pow(Math.sin(dLon/2),2);
        return 2*6371*Math.asin(Math.sqrt(h));
    }

    private Location location(Terminal terminal) {
        return new Location(terminal.address(),terminal.longitude(),terminal.latitude(),Instant.now());
    }
}
