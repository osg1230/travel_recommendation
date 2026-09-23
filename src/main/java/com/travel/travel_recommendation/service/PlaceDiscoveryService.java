package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
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
    private final PlatformTransactionManager transactions;

    public int refresh(UUID jobId,long destinationId,String login,byte[] hash) {
        var before=candidates.find(jobId,destinationId,login,hash);
        String region=before.destination().region();
        List<KakaoPlace> found=new ArrayList<>();
        // 외부 조회는 DB 잠금 밖에서 한다. 세 종류 조회가 끝나야 한 번에 저장한다.
        for(String type:List.of("ATTRACTION","RESTAURANT","ACCOMMODATION")) found.addAll(maps.searchPlaces(region,type));
        found.addAll(maps.searchTemples(region));
        found.addAll(touristCathedrals(maps.searchCathedrals(region)));
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
                if(existing.isPresent()) {
                    var place=existing.get();
                    if(!place.isActive() || !place.getRegion().equals(region) || !place.getPlaceType().equals(data.type())) continue;
                    place.refreshKakao(data);
                } else places.save(Place.fromKakao(data));
                count++;
            }
            places.flush(); return count;
        });
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
