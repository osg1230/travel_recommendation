package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceDiscoveryService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PlaceDiscoveryService.class);
    private final PlaceCandidateService candidates;
    private final PlaceRepository places;
    private final KakaoMapClient maps;
    private final TourApiClient tour;
    private final DestinationLandmarkService landmarkNames;
    private final tools.jackson.databind.ObjectMapper mapper;
    private final PlatformTransactionManager transactions;

    public int refresh(UUID jobId,long destinationId,String login,byte[] hash) {
        var before=candidates.find(jobId,destinationId,login,hash);
        String region=before.destination().region();
        List<KakaoPlace> found=new ArrayList<>();
        // 외부 조회는 DB 잠금 밖에서 한다. 세 종류 조회가 끝나야 한 번에 저장한다.
        // 관광지는 몇 번째로 찾았는지(0부터)를 함께 기억한다. 후보 수를 줄일 때 이름순 대신 이 순서를 쓴다.
        // 대표 관광지(관광공사 인기 순서 + GPT 제안 중 카카오에서 확인된 곳)를 맨 앞에 두고, "○○ 명소" 검색·사찰·성당은 그 뒤 순서로 둔다.
        // 대표 관광지 목록이 없으면(키 없음·실패) 예전처럼 "○○ 명소" 검색 순서만 쓴다.
        Map<String,Integer> ranks=new HashMap<>();
        var landmarks=listedLandmarks(landmarkNames.names(destinationId),region); rank(landmarks,ranks,0); found.addAll(landmarks);
        // 인기 음식점·숙소(같은 연관 관광지 자료)도 카카오에서 확인된 곳만 그 종류의 후보 맨 앞에 두고 '인기' 표시를 남긴다.
        // 일정의 식사 고르기에서 가까운 음식점 중 인기 음식점을 먼저 고르는 데 쓴다.
        Set<String> popularIds=new HashSet<>();
        for(var list:List.of(listedPopular(landmarkNames.restaurants(destinationId),region,maps::findRestaurant),
                listedPopular(landmarkNames.stays(destinationId),region,maps::findStay))) {
            rank(list,ranks,0); list.forEach(p->popularIds.add(p.sourceId())); found.addAll(list);
        }
        int after=landmarks.size();
        for(String type:List.of("ATTRACTION","RESTAURANT","ACCOMMODATION")) {
            var list=maps.searchPlaces(region,type);
            if("ATTRACTION".equals(type)) rank(list,ranks,after);
            found.addAll(list);
        }
        var temples=touristTemples(maps.searchTemples(region)); rank(temples,ranks,after); found.addAll(temples);
        var cathedrals=touristCathedrals(maps.searchCathedrals(region)); rank(cathedrals,ranks,after); found.addAll(cathedrals);
        var transaction=new TransactionTemplate(transactions); transaction.setTimeout(10);
        return transaction.execute(status->{
            // 같은 지역의 동시 수집을 직렬화하고, 조회 중 만료·권한 변경도 다시 검사한다.
            places.lockDestination(destinationId).orElseThrow(()->new RecommendationException("PLACES_CHANGED","지역 정보가 변경됐어요. 다시 추천해 주세요."));
            var current=candidates.find(jobId,destinationId,login,hash);
            if(!before.destination().equals(current.destination())) throw new RecommendationException("PLACES_CHANGED","지역 정보가 변경됐어요.");
            Set<String> ids=new HashSet<>(); int count=0;
            for(var data:found) {
                if(!region.equals(data.region()) || !ids.add(data.sourceId())) continue;
                var existing=places.findBySourceProviderAndSourcePlaceId("KAKAO",data.sourceId());
                Place place;
                if(existing.isPresent()) {
                    place=existing.get();
                    if(!place.isActive() || !place.getRegion().equals(region) || !place.getPlaceType().equals(data.type())) continue;
                    place.refreshKakao(data);
                } else place=places.save(Place.fromKakao(data));
                Integer rank=ranks.get(data.sourceId());
                String details=rank==null?null:PlaceRankDetails.write(place,rank,popularIds.contains(data.sourceId()),mapper);
                if(details!=null) place.updateDetails(details);
                count++;
            }
            places.flush(); return count;
        });
    }

    /**
     * 수정 요청에서 콕 집었지만 후보에 없는 관광지를 이 지역의 카카오 관광지로 찾아 저장한다(예: "하조대 넣어 줘").
     * 관광명소·문화시설 분류이고 주소가 이 지역인 곳 중 이름이 서로 포함되는 첫 결과만 쓴다. 못 찾으면 비어 있다.
     */
    public Optional<PlaceCandidate> findAttraction(long destinationId,String region,String name) {
        String wanted=KakaoMapClient.compactName(name==null?"":name);
        if(wanted.length()<2 || name.length()>40) return Optional.empty();
        List<KakaoPlace> found;
        try { found=maps.searchNationwide(name.strip(),List.of(region)); }
        catch(RecommendationException error) { log.warn("수정 요청 장소 찾기 실패 name={} ({})",name,error.getCode()); return Optional.empty(); }
        var match=found.stream().filter(p->{ String n=KakaoMapClient.compactName(p.name()); return n.length()>=2 && (n.contains(wanted) || wanted.contains(n)); })
            .findFirst();
        if(match.isEmpty()) return Optional.empty();
        var transaction=new TransactionTemplate(transactions); transaction.setTimeout(10);
        return Optional.ofNullable(transaction.execute(status->{
            // 장소 목록 수집과 같은 순서로 잠가 같은 장소를 동시에 두 번 저장하지 않는다.
            places.lockDestination(destinationId).orElseThrow(()->new RecommendationException("PLACES_CHANGED","지역 정보가 변경됐어요. 다시 추천해 주세요."));
            var data=match.get();
            var existing=places.findBySourceProviderAndSourcePlaceId("KAKAO",data.sourceId());
            Place place;
            if(existing.isPresent()) {
                place=existing.get();
                if(!place.isActive() || !region.equals(place.getRegion()) || !"ATTRACTION".equals(place.getPlaceType())) return null;
                place.refreshKakao(data);
            } else place=places.save(Place.fromKakao(data));
            places.flush();
            return candidates.candidate(place);
        }));
    }

    /** 목록에서의 순서를 offset부터 기록한다. 여러 검색에 나온 곳은 먼저 기록한 순서를 둔다. */
    private static void rank(List<KakaoPlace> list,Map<String,Integer> ranks,int offset) {
        for(int i=0;i<list.size();i++) ranks.putIfAbsent(list.get(i).sourceId(),offset+i);
    }

    /**
     * 대표 관광지 이름마다 이 지역의 카카오 장소를 찾아 제안 순서대로 돌려준다. 찾지 못한 이름은 버린다.
     * 사찰·성당은 대표 관광지 목록에 있어도 동네 절·본당일 수 있어, 관광공사에 관광지로 등록된 곳만 쓴다(사찰 검색과 같은 기준).
     */
    private List<KakaoPlace> listedLandmarks(List<String> names,String region) {
        List<KakaoPlace> listed=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(String name:names) {
            Optional<KakaoPlace> match;
            try { match=maps.findLandmark(name,region); }
            catch(RecommendationException error) { log.warn("대표 관광지 찾기 실패 name={} ({})",name,error.getCode()); continue; }
            if(match.isEmpty() || !ids.add(match.get().sourceId())) continue;
            var place=match.get();
            if(place.category().startsWith("문화,예술 > 종교")) {
                try { if(!tour.configured() || !nearTourFacility(place,TEMPLE_METERS)) continue; }
                catch(RecommendationException error) { log.warn("관광공사 조회 실패로 대표 관광지 사찰·성당을 넣지 않았어요 ({})",error.getCode()); continue; }
            }
            listed.add(place);
        }
        return listed;
    }

    /** 인기 음식점·숙소 이름마다 이 지역의 카카오 장소를 찾아 순서대로 돌려준다. 찾지 못한 이름은 버린다. */
    private List<KakaoPlace> listedPopular(List<String> names,String region,
                                          java.util.function.BiFunction<String,String,Optional<KakaoPlace>> finder) {
        List<KakaoPlace> listed=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(String name:names) {
            try { finder.apply(name,region).filter(p->ids.add(p.sourceId())).ifPresent(listed::add); }
            catch(RecommendationException error) { log.warn("인기 음식점·숙소 찾기 실패 name={} ({})",name,error.getCode()); }
        }
        return listed;
    }

    /** 사찰 후보 중 관광지로 받는 수와, 관광공사 관광지와 같은 곳으로 보는 거리(m). 큰 절은 경내가 넓어 성당보다 넉넉히 본다. */
    static final int MAX_TEMPLES=2, TEMPLE_METERS=1000;

    /**
     * 카카오 사찰 후보 중 관광공사에 관광지로 등록된 곳만 검색 순서대로 MAX_TEMPLES곳까지 관광지 후보로 쓴다.
     * "○○ 사찰" 검색은 경주에서는 불국사·석굴암을 주지만 강릉에서는 동네 절이 앞에 나와, 그대로 넣으면 일정에 들어갔다.
     * 관광공사에서 절 이름으로 찾아 TEMPLE_METERS 안에 있으면 같은 곳으로 본다. 관광공사 키가 없거나 조회에 실패하면 사찰은 넣지 않는다.
     */
    private List<KakaoPlace> touristTemples(List<KakaoPlace> temples) {
        if(temples.isEmpty() || !tour.configured()) return List.of();
        List<KakaoPlace> listed=new ArrayList<>();
        for(var temple:temples) {
            if(listed.size()==MAX_TEMPLES) break;
            try { if(nearTourFacility(temple,TEMPLE_METERS)) listed.add(temple); }
            catch(RecommendationException error) { log.warn("관광공사 사찰 조회 실패로 남은 사찰 후보를 넣지 않았어요 ({})",error.getCode()); break; }
        }
        return listed;
    }

    /** 관광공사 관광지·문화시설 중 이 장소에서 meters 안에 있는 곳이 있는지. "대한불교조계종 ○○사"처럼 종단 이름이 붙은 곳은 마지막 단어(절 이름)로 찾는다. */
    private boolean nearTourFacility(KakaoPlace place,int meters) {
        String name=place.name().substring(place.name().lastIndexOf(' ')+1);
        return tour.searchFacilities(name).stream().anyMatch(t->meters(place.longitude(),place.latitude(),t.longitude(),t.latitude())<=meters);
    }

    /**
     * 카카오 성당 후보 중 관광공사에 관광지로 등록된 성당(예: 전주전동성당)과 300m 안에 있는 곳만 관광지 후보로 쓴다.
     * 동네 본당을 관광지로 넣지 않기 위해서다. 이름은 출처마다 달라(예: "서울 명동성당"·"명동대성당") 위치로 맞춘다.
     * 관광공사 키가 없거나 조회에 실패하면 성당은 넣지 않고 나머지 장소 수집은 계속한다.
     */
    private List<KakaoPlace> touristCathedrals(List<KakaoPlace> cathedrals) {
        if(cathedrals.isEmpty() || !tour.configured()) return List.of();
        List<TourApiClient.SearchPlace> listed;
        try { listed=tour.searchFacilities("성당"); }
        catch(RecommendationException error) { log.warn("관광공사 성당 조회 실패로 성당 후보를 넣지 않았어요 ({})",error.getCode()); return List.of(); }
        return cathedrals.stream().filter(c->listed.stream()
            .anyMatch(t->meters(c.longitude(),c.latitude(),t.longitude(),t.latitude())<=300)).toList();
    }

    // 짧은 거리라 평면으로 보고 계산한다.
    private static double meters(double lon1,double lat1,double lon2,double lat2) {
        double x=(lon2-lon1)*Math.cos(Math.toRadians((lat1+lat2)/2)), y=lat2-lat1;
        return Math.sqrt(x*x+y*y)*111_320;
    }
}
