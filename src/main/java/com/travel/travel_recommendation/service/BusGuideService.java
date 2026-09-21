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
        String arrivalId=buses.terminalId(target.busName());
        // 지금 여행지에서 출발할 때는 그 터미널 하나만, 그 밖에는 이름이 들어간 터미널을 최대 몇 곳까지 찾는다.
        var origins=fromCurrentRegion?List.of(new IntercityBusClient.Terminal(buses.terminalId(keyword),keyword))
            :buses.searchTerminals(keyword,MAX_ORIGINS+1);
        origins=origins.stream().filter(t->!t.id().equals(arrivalId)).toList();
        String label=fromCurrentRegion?destination+"(지금 여행지)":keyword;
        if(origins.isEmpty())
            return new BusGuide(label,to,today,tripDate,List.of(),"'"+keyword+"' 이름의 시외버스 터미널을 찾지 못했어요. 도시 이름이나 터미널 이름을 다시 적어 주세요.");
        String more=origins.size()>MAX_ORIGINS?" 터미널이 많아 "+MAX_ORIGINS+"곳만 조회했어요. 터미널 이름을 적으면 더 정확해요.":"";
        List<BusGuide.Route> routes=new ArrayList<>();
        for(var origin:origins.stream().limit(MAX_ORIGINS).toList()) {
            var trips=buses.trips(origin.id(),arrivalId,today);
            if(trips.isEmpty()) continue;
            routes.add(new BusGuide.Route("시외버스",origin.name(),target.busName(),trips.size(),
                trips.getFirst().departure().toLocalTime(),trips.getLast().departure().toLocalTime(),
                trips.stream().mapToInt(IntercityBusClient.Trip::minutes).min().orElseThrow(),
                trips.stream().map(IntercityBusClient.Trip::charge).filter(Objects::nonNull).min(Integer::compare).orElse(null)));
        }
        routes.sort(Comparator.comparingInt(BusGuide.Route::fastestMinutes));
        String message=routes.isEmpty()
            ?"오늘 운행 정보가 등록된 직행 시외버스를 찾지 못했어요. 시외버스 정보에 없는 노선일 수 있으니 터미널이나 예매처에서 확인해 주세요."+more
            :more.isEmpty()?null:more.strip();
        return new BusGuide(label,to,today,tripDate,routes,message);
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
