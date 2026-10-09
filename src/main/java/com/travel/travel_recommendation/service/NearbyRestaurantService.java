package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.Input;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정 작업이 시작되기 전에, 일정에 넣기로 고른 관광지와 숙소 후보 주변의 음식점을 카카오에서 찾아 요청의 음식점 후보에 더한다.
 * 여행지 전체에서 찾은 음식점 몇 곳만으로는 제주처럼 넓은 지역에서 점심을 먹으러 1시간 넘게 되돌아가는 일이 생기기 때문이다.
 * 목적 시설 검색과 같이 읽기 → 검색(DB 연결 없음) → 저장을 나눈다. 검색이 실패해도 일정은 기존 음식점 후보로 그대로 만든다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class NearbyRestaurantService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(NearbyRestaurantService.class);
    /** 한 지점에서 찾는 반경(m)과 받아 오는 음식점 수. */
    static final int RADIUS_METERS=3000, PER_POINT=3;
    /** 찾는 지점 수의 상한. 이미 찾은 지점과 이 거리(km) 안의 지점은 다시 찾지 않는다(카카오 호출 수를 줄인다). */
    static final int MAX_POINTS=12;
    static final double SAME_POINT_KM=1.5;
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final PlaceCandidateService candidates;
    private final KakaoMapClient maps;
    private final ObjectMapper mapper;
    private final ObjectProvider<NearbyRestaurantService> self;

    public record Task(UUID jobId, String requestJson, Input input) {}

    /** 대기 중인 일정 작업에 주변 음식점을 붙인다. 이미 붙였거나 찾을 곳이 없으면 요청을 바꾸지 않는다. */
    public void enrich(UUID jobId) {
        var task=self.getObject().task(jobId);
        if(task==null) return;
        var found=search(task.input());
        if(!found.isEmpty()) self.getObject().save(task,found);
    }

    @Transactional(readOnly=true,timeout=10)
    public Task task(UUID jobId) {
        var job=jobs.findById(jobId).orElse(null);
        if(job==null || !"ITINERARY".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        return new Task(jobId,job.getRequestJson(),mapper.readValue(job.getRequestJson(),Input.class));
    }

    /** 외부 호출만 하는 단계라 트랜잭션을 두지 않는다. 한 지점의 조회가 실패하면 그 지점만 건너뛴다. */
    List<KakaoPlace> search(Input input) {
        String region=input.places().destination().region();
        Map<String,KakaoPlace> found=new LinkedHashMap<>();
        for(var point:points(input)) {
            try { maps.searchRestaurantsNear(point.longitude(),point.latitude(),region,RADIUS_METERS,PER_POINT).forEach(p->found.putIfAbsent(p.sourceId(),p)); }
            catch(RecommendationException error) {
                log.warn("주변 음식점 찾기를 건너뜀 place={} ({})",point.placeId(),error.getCode());
                // 인증·사용량 문제면 다른 지점도 실패하므로 더 부르지 않는다.
                if(Set.of("MAP_NOT_CONFIGURED","MAP_AUTH","MAP_RATE_LIMIT").contains(error.getCode())) break;
            }
        }
        return List.copyOf(found.values());
    }

    /** 음식점을 찾을 지점: 일정에 넣기로 고른 관광지와 숙소 후보 중 좌표가 있는 곳. 서로 가까운 곳은 한 번만 찾는다. */
    static List<PlaceCandidate> points(Input input) {
        var all=new ArrayList<>(input.places().attractions()); all.addAll(input.places().accommodations());
        var points=new ArrayList<PlaceCandidate>();
        for(var place:all) {
            if(place.longitude()==null || place.latitude()==null || points.size()==MAX_POINTS) continue;
            if(points.stream().noneMatch(p->kilometers(p,place)<SAME_POINT_KM)) points.add(place);
        }
        return points;
    }

    /**
     * 요청이 그대로일 때만 찾은 음식점을 저장하고 요청의 음식점 후보에 더한다.
     * 이미 저장된 음식점은 하루가 지나 후보 조건(조회 후 24시간)을 벗어난 경우에만 새 조회 결과로 고친다.
     * 다른 작업이 쓰고 있는 장소를 고치면 그 작업이 '장소 정보 변경'으로 실패할 수 있어서다.
     */
    @Transactional(timeout=10)
    public void save(Task task,List<KakaoPlace> found) {
        var job=jobs.lockById(task.jobId()).orElse(null);
        if(job==null || !"QUEUED".equals(job.getStatus()) || !task.requestJson().equals(job.getRequestJson())) return;
        var input=task.input();
        var restaurants=new ArrayList<>(input.places().restaurants());
        Set<Long> ids=new HashSet<>(); restaurants.forEach(r->ids.add(r.placeId()));
        var dayAgo=KoreaTime.now().minusHours(24);
        for(var data:found) {
            var existing=places.findBySourceProviderAndSourcePlaceId("KAKAO",data.sourceId());
            Place place;
            if(existing.isPresent()) {
                place=existing.get();
                if(ids.contains(place.getId())) continue;
                // 다른 지역·유형(예: 관광지)으로 저장된 곳은 바꾸지 않는다.
                if(!place.isActive() || !place.getRegion().equals(data.region()) || !"RESTAURANT".equals(place.getPlaceType())) continue;
                if(place.getVerifiedAt().isBefore(dayAgo)) place.refreshKakao(data);
            } else place=places.save(Place.fromKakao(data));
            if(ids.add(place.getId())) restaurants.add(candidates.candidate(place));
        }
        if(restaurants.size()==input.places().restaurants().size()) return;
        places.flush();
        var view=input.places();
        var withNearby=new PlaceCandidateView(view.jobId(),view.destination(),view.durationDays(),view.accommodationNeeded(),
            view.attractions(),view.accommodations(),restaurants);
        var enriched=input.withPlaces(withNearby);
        job.attachItineraryRequest(mapper.writeValueAsString(enriched));
        jobs.flush();
    }

    private static double kilometers(PlaceCandidate a,PlaceCandidate b) {
        double x=(a.longitude()-b.longitude())*Math.cos(Math.toRadians(a.latitude()));
        double y=a.latitude()-b.latitude();
        return Math.sqrt(x*x+y*y)*111.0;
    }
}
