package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.BusGuide;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 요청 시 지역 간 버스 안내(SFR-06-05·06). 일정 초안 화면에서 사용자가 누를 때만 조회한다.
 * 여행지로 갈 때는 사용자가 알려 준 출발 도시(입력 또는 현재 위치)에서, 주변 지역으로 갈 때는 지금 여행지에서 출발하는 것을 기본으로 한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class BusGuideService {
    /** 한 번에 조회하는 출발 터미널 수. 도시에 터미널이 많으면 사용자가 더 구체적으로 적게 한다. */
    static final int MAX_ORIGINS=5;
    private final ItineraryJobService itineraries;
    private final IntercityBusClient buses;
    private final KakaoMapClient maps;

    public BusGuide guide(UUID jobId,String login,byte[] guestHash,String to,String from,Double longitude,Double latitude) {
        // 본인(또는 같은 비회원 세션)의 완성된 일정에서만 조회한다.
        var view=itineraries.view(jobId,login,guestHash);
        if(view.result()==null) throw new RecommendationException("BUS_NO_PLAN","일정 초안이 완성된 뒤에 버스 정보를 찾을 수 있어요.");
        String destination=view.input().places().destination().name();
        // 도착지는 이 일정의 여행지나 화면에 추천된 주변 지역만 고를 수 있다.
        Set<String> allowed=new LinkedHashSet<>(List.of(destination));
        if(view.result().nearby()!=null) view.result().nearby().regions().forEach(r->allowed.add(r.name()));
        if(to==null || !allowed.contains(to)) throw new RecommendationException("BUS_INPUT","도착 지역을 목록에서 다시 골라 주세요.");
        var target=NearbyRegionService.TERMINALS.get(to);
        if(target==null) throw new RecommendationException("BUS_UNSUPPORTED",to+"은(는) 버스 터미널 정보가 없어 안내하지 못해요.");

        // 출발지 정하기: 현재 위치 → 입력한 도시·터미널 → (주변 지역으로 갈 때) 지금 여행지 순서다.
        String keyword;
        boolean fromCurrentRegion=false;
        if(longitude!=null && latitude!=null) keyword=maps.cityKeyword(longitude,latitude);
        else if(from!=null && !from.isBlank()) keyword=checkedKeyword(from);
        else if(!to.equals(destination) && NearbyRegionService.TERMINALS.containsKey(destination)) {
            keyword=NearbyRegionService.TERMINALS.get(destination).busName(); fromCurrentRegion=true;
        } else throw new RecommendationException("BUS_ORIGIN","출발 도시나 터미널 이름을 적거나 '내 위치로 찾기'를 눌러 주세요.");

        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        LocalDate tripDate=view.input().conditions().startDate();
        String label=fromCurrentRegion?destination+"(지금 여행지)":keyword;
        var found=search(keyword,to,fromCurrentRegion?destination:null,today);
        var routes=found.routes(); var unavailable=found.unavailable();
        boolean originFound=found.originFound(), trimmed=found.trimmed();
        var authError=found.authError();
        if(unavailable.size()==IntercityBusClient.Service.values().length) throw authError;
        String skipped=unavailable.isEmpty()?"":" "+String.join("·",unavailable)+" 정보는 지금 확인할 수 없어 제외했어요.";
        if(!originFound)
            return new BusGuide(label,to,today,tripDate,List.of(),"'"+keyword+"' 이름의 시외·고속버스 터미널을 찾지 못했어요. 도시 이름이나 터미널 이름을 다시 적어 주세요."+skipped);
        String more=(trimmed?" 터미널이 많아 이름 기준 "+MAX_ORIGINS+"곳만 조회했어요. 터미널 이름을 적으면 더 정확해요.":"")+skipped;
        routes.sort(Comparator.comparingInt(BusGuide.Route::fastestMinutes));
        String message=routes.isEmpty()
            ?"오늘 운행 정보가 등록된 직행 시외·고속버스를 찾지 못했어요. 버스 정보에 없는 노선일 수 있으니 터미널이나 예매처에서 확인해 주세요."+more
            :more.isEmpty()?null:more.strip();
        return new BusGuide(label,to,today,tripDate,routes,message);
    }

    /** 버스 조회 결과. unavailable은 인증키 활용신청이 확인되지 않아 조회하지 못한 서비스(시외·고속)다. */
    private record Search(List<BusGuide.Route> routes,boolean originFound,boolean trimmed,List<String> unavailable,RecommendationException authError) {}

    /**
     * keyword 이름의 터미널(currentRegion이 있으면 그 여행지 터미널)에서 to의 터미널까지 date에 운행하는 직행 시외·고속버스를 찾는다.
     * 두 서비스는 터미널 ID 체계가 달라 각각 찾고, 한쪽만 인증이 안 되면 나머지로 안내한다.
     */
    private Search search(String keyword,String to,String currentRegion,LocalDate date) {
        var target=NearbyRegionService.TERMINALS.get(to);
        var current=currentRegion==null?null:NearbyRegionService.TERMINALS.get(currentRegion);
        boolean fromCurrentRegion=current!=null;
        List<BusGuide.Route> routes=new ArrayList<>();
        boolean originFound=false, trimmed=false;
        List<String> unavailable=new ArrayList<>();
        RecommendationException authError=null;
        for(var service:IntercityBusClient.Service.values()) try {
            String arrivalName=service==IntercityBusClient.Service.INTERCITY?target.busName():target.expressName();
            var arrivalIds=buses.terminalIds(service,arrivalName);
            if(arrivalIds.isEmpty()) continue;
            // 지금 여행지에서 출발하면 그 터미널만, 그 밖에는 이름이 들어간 터미널을 찾는다.
            String currentName=fromCurrentRegion?(service==IntercityBusClient.Service.INTERCITY?current.busName():current.expressName()):null;
            var found=fromCurrentRegion
                ?buses.terminalIds(service,currentName).stream().map(id->new IntercityBusClient.Terminal(id,currentName)).toList()
                :buses.searchTerminals(service,keyword,20);
            // 고속버스는 같은 이름이 승강장별로 여러 ID라 이름으로 묶고, 이름 기준으로 최대 몇 곳만 조회한다.
            Map<String,List<String>> originIds=new LinkedHashMap<>();
            for(var terminal:found) if(!arrivalIds.contains(terminal.id())) originIds.computeIfAbsent(terminal.name(),k->new ArrayList<>()).add(terminal.id());
            if(originIds.isEmpty()) continue;
            originFound=true;
            if(originIds.size()>MAX_ORIGINS) trimmed=true;
            for(var origin:originIds.entrySet().stream().limit(MAX_ORIGINS).toList()) {
                // 같은 출발지 이름의 여러 ID·도착 ID에서 나온 배차를 합치고, 같은 배차는 한 번만 센다.
                Map<String,IntercityBusClient.Trip> trips=new TreeMap<>();
                for(String departureId:origin.getValue()) for(String arrivalId:arrivalIds)
                    for(var trip:buses.trips(service,departureId,arrivalId,date)) trips.putIfAbsent(trip.departure()+"|"+trip.arrival()+"|"+trip.grade(),trip);
                if(trips.isEmpty()) continue;
                var list=trips.values().stream().sorted(Comparator.comparing(IntercityBusClient.Trip::departure)).toList();
                routes.add(new BusGuide.Route(service.label(),origin.getKey(),arrivalName,list.size(),
                    BusGuide.clock(list.getFirst().departure(),date),BusGuide.clock(list.getLast().departure(),date),
                    list.stream().mapToInt(IntercityBusClient.Trip::minutes).min().orElseThrow(),
                    list.stream().map(IntercityBusClient.Trip::charge).filter(Objects::nonNull).min(Integer::compare).orElse(null)));
            }
        } catch(RecommendationException error) {
            if(!"BUS_AUTH".equals(error.getCode())) throw error;
            unavailable.add(service.label()); authError=error;
        }
        return new Search(routes,originFound,trimmed,unavailable,authError);
    }

    /**
     * 집에서 여행지까지 대중교통 편도 시간(집 출발·귀가 설정용). 출발 도시 터미널에서 여행지 터미널까지 오늘 운행하는
     * 직행 시외·고속버스의 가장 짧은 소요시간이며, 집 ↔ 터미널 이동은 들어 있지 않다. 찾지 못하면 비어 있다.
     */
    public OptionalInt fastestMinutes(String cityKeyword,String destination) {
        if(!buses.configured() || NearbyRegionService.TERMINALS.get(destination)==null) return OptionalInt.empty();
        String keyword=checkedKeyword(cityKeyword);
        try {
            return search(keyword,destination,null,LocalDate.now(ZoneId.of("Asia/Seoul"))).routes().stream()
                .mapToInt(BusGuide.Route::fastestMinutes).min();
        } catch(RecommendationException error) { return OptionalInt.empty(); }
    }

    // 입력한 출발지는 한글·영문·숫자·괄호·공백만 받는다. 외부 API 검색어로 그대로 넘기기 때문이다.
    private String checkedKeyword(String from) {
        String keyword=from.strip().replaceAll("\\s+"," ");
        if(keyword.length()>20 || !keyword.matches("[가-힣A-Za-z0-9() ]+"))
            throw new RecommendationException("BUS_INPUT","출발지는 20자 이내의 도시나 터미널 이름으로 적어 주세요.");
        // "동서울터미널", "강릉시"처럼 적어도 TAGO 터미널 이름과 맞도록 흔한 꼬리말을 뗀다.
        String trimmed=keyword.replaceAll("(종합)?(시외|고속)?버스터미널$|터미널$","").replaceAll("(시|군)$","").strip();
        return trimmed.isEmpty()?keyword:trimmed;
    }
}
