package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 여행지(도시)마다 "할 수 있는 것" 목록({@link DestinationProfile})을 관광공사 등록 장소로 만들고 여행지 행에 보관한다.
 * 지역의 장소를 새 분류체계 대분류(자연·역사·문화·체험·레저·쇼핑)마다 한 번씩 받아 소분류별로 센다(여행지당 약 6번 호출,
 * 일반구가 있는 시는 구마다). 외부 조회 동안 DB 연결을 잡지 않도록 읽기 → 외부 조회 → 저장을 나눈다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class DestinationProfileService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DestinationProfileService.class);
    /** 세는 대분류: 자연관광·역사관광·문화관광·체험관광·레저스포츠·쇼핑(시장). 음식·숙박은 여행 목적 근거로 쓰지 않아 뺀다. */
    static final List<String> CLASSES=List.of("NA","HS","VE","EX","LS","SH");
    /** 대분류 하나에 읽는 최대 쪽 수(쪽당 1,000곳). 서울 문화관광처럼 많은 곳도 대부분 담긴다. */
    static final int MAX_PAGES=3;
    private final PlaceRepository places;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    private final AtomicBoolean preparing=new AtomicBoolean();

    /** 여행지의 "할 수 있는 것" 목록. 30일 안에 만든 것이 있으면 그것을, 없으면 관광공사에서 새로 세어 보관한다. 만들지 못하면 빈 목록이다. */
    public Map<String,DestinationProfile.Category> profile(long destinationId) {
        var destination=places.findById(destinationId).filter(p->"DESTINATION".equals(p.getPlaceType())).orElse(null);
        if(destination==null) return Map.of();
        var saved=DestinationProfile.read(destination,Instant.now(),mapper);
        if(saved!=null) return saved;
        if(!tour.configured()) return Map.of();
        try {
            var made=build(destination.getRegion());
            if(made.isEmpty()) return Map.of();
            save(destinationId,made);
            return made;
        } catch(RecommendationException error) {
            // 관광공사 오류·한도면 이번에는 근거 없이 넘어가고 다음 요청에서 다시 만든다(일부만 모은 목록은 보관하지 않는다).
            log.warn("여행지 목록을 만들지 못함 destination={} ({})",destination.getName(),error.getCode());
            return Map.of();
        }
    }

    /**
     * 목록이 없거나 오래된 여행지를 모두 채운다. 추천이 들어오면 RecommendationJobWorker가 별도 스레드에서 부른다.
     * 처음 한 번은 28곳에 약 200번 호출해 몇 분 걸리므로, 이미 돌고 있으면 바로 돌아간다.
     */
    public void prepareMissing() {
        if(!preparing.compareAndSet(false,true)) return;
        try { for(var destination:places.findDestinations()) profile(destination.getId()); }
        finally { preparing.set(false); }
    }

    /** 지역의 시·도(또는 시·군·구마다)에서 대분류별 장소를 받아 소분류로 센다. 지역 코드를 찾지 못하면 빈 목록이다. */
    private Map<String,DestinationProfile.Category> build(String region) {
        var codes=tour.regionCodes(region).orElse(null);
        if(codes==null) return Map.of();
        List<String> districts=codes.districts().isEmpty()?Collections.singletonList(null):codes.districts();
        List<TourApiClient.ClassifiedPlace> found=new ArrayList<>();
        for(String district:districts) for(String topClass:CLASSES)
            found.addAll(tour.placesInClass(codes.province(),district,topClass,MAX_PAGES));
        return DestinationProfile.summarize(found);
    }

    /** 같은 여행지를 동시에 저장하지 않도록 여행지 행을 잠그고 쓴다. 다른 details_json 정보는 그대로 둔다. */
    private void save(long destinationId,Map<String,DestinationProfile.Category> categories) {
        var transaction=new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status->places.lockById(destinationId).ifPresent(place->{
            String json=DestinationProfile.write(place,categories,Instant.now(),mapper);
            if(json!=null) { place.updateDetails(json); places.flush(); }
        }));
    }
}
