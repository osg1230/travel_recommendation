package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.Instant;
import java.time.YearMonth;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 여행지(도시)의 대표 관광지 이름 목록을 준비한다. 관광공사 연관 관광지(티맵 이동 데이터)로 정한 인기 순서를 먼저 쓰고,
 * GPT가 제안한 대표 관광지 중 빠진 곳을 뒤에 붙인다. 같은 연관 관광지 자료에서 인기 음식점·숙소 이름도 함께 만든다(추가 호출 없음).
 * 목록은 여행지 행에 보관해 다시 계산하지 않는다({@link DestinationLandmarks}).
 * 이름은 카카오 검색어로만 쓰며, 실제 장소인지·그 지역 안인지는 {@link PlaceDiscoveryService}가 카카오로 확인한다.
 * 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 외부 조회 → 저장을 나눈다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class DestinationLandmarkService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(DestinationLandmarkService.class);
    /** 한 여행지에 보관하는 이름 수. 이름마다 카카오 검색을 한 번씩 하므로 상한을 둔다. */
    static final int MAX_NAMES=30;
    /** 시·군·구 하나는 최대 3쪽(3,000행)까지 읽고, 구가 여러 개인 시·도(부산·서울 등)는 구마다 1쪽만 읽어 호출 수를 줄인다. */
    static final int MAX_PAGES=3;
    private final PlaceRepository places;
    private final TourApiClient tour;
    private final OpenAiLandmarkClient ai;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;

    /** 계산한 목록. popular는 관광공사 연관 관광지를 받았는지다(못 받았으면 하루 뒤 다시 계산한다). */
    record Made(DestinationLandmarks.Lists lists,boolean popular) {}

    /** 대표 관광지 이름(관광공사 인기 순서 + GPT 제안). */
    public List<String> names(long destinationId) { return lists(destinationId).names(); }
    /** 인기 음식점 이름(관광공사 연관 관광지의 음식 순위). */
    public List<String> restaurants(long destinationId) { return lists(destinationId).restaurants(); }
    /** 인기 숙소 이름(관광공사 연관 관광지의 숙박 순위). */
    public List<String> stays(long destinationId) { return lists(destinationId).stays(); }

    /** 보관한 목록이 있으면 그것을, 없거나 오래됐으면 새로 만들어 보관한 뒤 돌려준다. 만들지 못하면 빈 목록이다. */
    private DestinationLandmarks.Lists lists(long destinationId) {
        var destination=places.findById(destinationId).filter(p->"DESTINATION".equals(p.getPlaceType())).orElse(null);
        if(destination==null) return DestinationLandmarks.Lists.EMPTY;
        var saved=DestinationLandmarks.read(destination,Instant.now(),mapper);
        if(saved!=null) return saved;
        var made=make(destination);
        save(destinationId,made);
        return made.lists();
    }

    /**
     * 추천된 여행지들의 목록을 미리 만들어 둔다. 추천 결과가 나오면 RecommendationJobWorker가 별도 스레드에서 부른다.
     * 사용자가 여행지를 고르기 전에 만들어 두면 장소 목록 화면이 관광공사·GPT 응답을 기다리지 않는다. 이미 있으면 건너뛴다.
     */
    public void prepare(Collection<Long> destinationIds) {
        for(long id:destinationIds) {
            try { lists(id); }
            catch(RuntimeException error) { log.warn("대표 관광지 목록을 미리 만들지 못함 destination={} ({})",id,error.toString()); }
        }
    }

    private Made make(Place destination) {
        var popular=DestinationLandmarks.Lists.EMPTY; boolean popularDone=!tour.configured();
        if(tour.configured()) {
            try { popular=popular(destination.getRegion()); popularDone=true; }
            catch(RecommendationException error) { log.warn("연관 관광지 조회를 건너뜀 destination={} ({})",destination.getName(),error.getCode()); }
        }
        List<String> suggested=List.of();
        if(ai.configured()) {
            try { suggested=ai.names(destination.getName(),destination.getRegion()); }
            catch(RecommendationException error) { log.warn("대표 관광지 제안을 건너뜀 destination={} ({})",destination.getName(),error.getCode()); }
        }
        // 인기 순서를 앞에 두고 GPT 제안 중 이미 있는 이름(띄어쓰기만 다른 것 포함)은 뺀다.
        List<String> names=new ArrayList<>(); Set<String> seen=new HashSet<>();
        for(String name:concat(popular.names(),suggested))
            if(names.size()<MAX_NAMES && seen.add(KakaoMapClient.compactName(name))) names.add(name);
        return new Made(new DestinationLandmarks.Lists(names,popular.restaurants(),popular.stays()),popularDone);
    }

    /**
     * 여행지 지역의 시·군·구마다 지난달 연관 관광지를 받아 인기 관광지·음식점·숙소 순서로 모은다(한 번 받은 행을 세 가지로 나눈다).
     * 지역 코드를 찾지 못하면 빈 목록이다.
     */
    private DestinationLandmarks.Lists popular(String region) {
        var codes=districtCodes(region);
        if(codes.isEmpty()) return DestinationLandmarks.Lists.EMPTY;
        var month=YearMonth.now(KoreaTime.ZONE).minusMonths(1);
        int pages=codes.size()>1?1:MAX_PAGES;
        List<TourApiClient.RelatedPlace> rows=new ArrayList<>();
        for(String code:codes) rows.addAll(tour.relatedPlaces(code,month,pages));
        var inside=Set.copyOf(codes);
        return new DestinationLandmarks.Lists(PopularPlaces.attractions(rows,inside,PopularPlaces.ATTRACTIONS),
            PopularPlaces.restaurants(rows,inside,PopularPlaces.RESTAURANTS),PopularPlaces.stays(rows,inside,PopularPlaces.STAYS));
    }

    /**
     * 여행지 지역 이름을 5자리 시·군·구 코드로 바꾼다. "강원특별자치도 강릉시" → [51150],
     * "경기도 수원시"처럼 일반구가 있는 시는 구 코드들([41111, 41113, 41115, 41117]),
     * "부산광역시"처럼 시·도 전체인 여행지는 그 안의 시·군·구 전부, 세종특별자치시는 [36110]이다.
     */
    private List<String> districtCodes(String region) {
        if(region==null || region.isBlank()) return List.of();
        String[] parts=region.strip().split("\\s+");
        var province=tour.districtCode(null,parts[0]).orElse(null);
        if(province==null) return List.of();
        if(province.length()==5) return List.of(province);
        if(parts.length==2) return tour.cityDistrictCodes(province,parts[1]).stream().map(code->province+code).toList();
        return tour.districtCodes(province).stream().map(code->province+code).toList();
    }

    /** 같은 여행지를 동시에 저장하지 않도록 여행지 행을 잠그고 쓴다. 다른 details_json 정보는 그대로 둔다. */
    private void save(long destinationId,Made made) {
        var transaction=new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status->places.lockById(destinationId).ifPresent(place->{
            String json=DestinationLandmarks.write(place,made.lists(),made.popular(),Instant.now(),mapper);
            if(json!=null) { place.updateDetails(json); places.flush(); }
        }));
    }

    private static List<String> concat(List<String> first,List<String> second) {
        List<String> all=new ArrayList<>(first); all.addAll(second); return all;
    }
}
