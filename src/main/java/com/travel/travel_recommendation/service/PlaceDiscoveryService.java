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
    private final PlaceCandidateService candidates;
    private final PlaceRepository places;
    private final KakaoMapClient maps;
    private final PlatformTransactionManager transactions;

    public int refresh(UUID jobId,long destinationId,String login,byte[] hash) {
        var before=candidates.find(jobId,destinationId,login,hash);
        String region=before.destination().region();
        List<KakaoPlace> found=new ArrayList<>();
        // 외부 조회는 DB 잠금 밖에서 한다. 세 종류 조회가 끝나야 한 번에 저장한다.
        for(String type:List.of("ATTRACTION","RESTAURANT","ACCOMMODATION")) found.addAll(maps.searchPlaces(region,type));
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
}
