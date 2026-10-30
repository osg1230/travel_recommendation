package com.travel.travel_recommendation.service;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.*;
import org.springframework.stereotype.Component;

@Component
@Profile({"sqlserver","sqlserver-windows"})
@ConditionalOnProperty(name="travel.jobs.enabled",havingValue="true",matchIfMissing=true)
@EnableScheduling
@RequiredArgsConstructor
public class RecommendationJobWorker {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(RecommendationJobWorker.class);
    private final RecommendationJobService service;
    private final DestinationRecommendationService recommendations;
    private final ItineraryJobService itineraries;
    private final PurposePlaceService purposes;
    private final RegionSuggestionService regions;
    private final DestinationPickService picks;
    private final FestivalPlaceService festivalPlaces;
    private final PlacePhotoService photos;
    private final NearbyRestaurantService restaurants;
    private final PlacePickService placePicks;
    private final DestinationLandmarkService landmarks;
    private final DestinationProfileService profiles;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void start() { service.recover(); service.cleanup(); ready=true; }

    // 단일 서버의 단일 실행기로 후보 조회와 추천을 순차 처리한다. 작업 생성 응답은 이 처리를 기다리지 않는다.
    @Scheduled(fixedDelay=1000)
    public void runQueued() {
        if(!ready) return;
        for(UUID id:service.queued()) {
            // 지역을 정하지 않고 원하는 여행을 말했으면 그 여행을 할 수 있는 여행지를 먼저 확인해 둔다(새 시·군은 여행지로 추가).
            // 그다음 구체적 목적의 시설을 찾아 둔다. 새로 추가한 여행지도 시설 검색 대상이 된다. 실패해도 추천은 그대로 진행한다.
            try { regions.enrich(id); } catch(RuntimeException e) { log.warn("지역 제안을 건너뜀 job={} ({})",id,e.toString()); }
            try { purposes.enrich(id); } catch(RuntimeException e) { log.warn("목적 시설 검색을 건너뜀 job={} ({})",id,e.toString()); }
            // 문장으로 입력했고 여행지가 5곳보다 많으면 AI가 여행지별 근거를 보고 후보 5곳을 고른다. 실패하면 기존 기준으로 고른다.
            try { picks.enrich(id); } catch(RuntimeException e) { log.warn("후보 고르기를 건너뜀 job={} ({})",id,e.toString()); }
            try { service.prepare(id); }
            catch(RuntimeException e) { service.fail(id); }
        }
        for(UUID id:recommendations.queued()) {
            var work=recommendations.claim(id);
            if(work==null) continue;
            try {
                var ranked=recommendations.generate(work);
                recommendations.complete(work,ranked);
                // 추천에 붙은 축제가 열리는 곳을 카카오 장소로 확인해 둔다. 일정에 축제를 넣을 때 쓰며, 실패해도 추천은 그대로다.
                try { festivalPlaces.attach(id,ranked); }
                catch(RuntimeException e) { log.warn("축제 장소 확인을 건너뜀 job={} ({})",id,e.toString()); }
                // 추천 이유에 나온 중심 장소의 관광공사 사진을 여행지 카드 사진으로 찾아 둔다(최대 10초). 한 번 찾은 사진은 30일 동안 다시 쓰고, 실패해도 추천은 그대로다.
                try { photos.findForDestinations(ranked,java.time.Instant.now().plusSeconds(10)); }
                catch(RuntimeException e) { log.warn("여행지 사진 찾기를 건너뜀 job={} ({})",id,e.toString()); }
                // 추천된 여행지의 대표 관광지 목록(관광공사 인기 순서 + GPT 제안)을 미리 만들어 둔다. 서울처럼 구가 많은 곳은
                // 관광공사를 수십 번 불러 오래 걸리므로, 다른 작업을 처리하는 이 실행기를 붙잡지 않게 별도 스레드에서 돌린다.
                var destinationIds=ranked.stream().map(r->r.destination().placeId()).toList();
                Thread.ofVirtual().name("destination-landmarks").start(()->landmarks.prepare(destinationIds));
                // 여행지마다 "할 수 있는 것" 목록(관광공사 분류별 장소 수)이 없거나 오래됐으면 같은 이유로 별도 스레드에서 채운다.
                // 처음 한 번은 28곳을 모두 채워 몇 분 걸리고, 이미 채우는 중이면 바로 돌아간다.
                Thread.ofVirtual().name("destination-profiles").start(profiles::prepareMissing);
            }
            catch(RecommendationException e) { recommendations.fail(work,e.getCode(),e.getMessage()); }
            catch(RuntimeException e) { recommendations.fail(work,"RECOMMENDATION_FAILED","추천 결과를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."); }
        }
        for(UUID id:itineraries.queued()) {
            // 관광지 후보를 AI가 갈 만한 순서로 정하면 그 순서로 다시 고른다. 실패해도 서버 규칙으로 고른 관광지로 일정을 만든다.
            try { placePicks.enrich(id); } catch(RuntimeException e) { log.warn("AI 관광지 순서 정하기를 건너뜀 job={} ({})",id,e.toString()); }
            // 고른 관광지·숙소 주변의 음식점을 먼저 찾아 요청에 더한다. 실패해도 기존 음식점 후보로 일정을 만든다.
            try { restaurants.enrich(id); } catch(RuntimeException e) { log.warn("주변 음식점 찾기를 건너뜀 job={} ({})",id,e.toString()); }
            var work=itineraries.claim(id);
            if(work==null) continue;
            var started=java.time.Instant.now();
            try {
                var result=itineraries.generate(work);
                // 일정에 들어간 관광지의 사진을 찾아 둔다. 작업 제한(2분) 안에서 최대 15초만 쓰고, 실패해도 일정은 그대로 완료한다.
                var until=java.time.Instant.now().plusSeconds(15);
                if(until.isAfter(started.plusSeconds(100))) until=started.plusSeconds(100);
                try { photos.findForItinerary(work.input(),result,until); } catch(RuntimeException e) { log.warn("장소 사진 찾기를 건너뜀 job={} ({})",id,e.toString()); }
                itineraries.complete(work,result);
            }
            catch(RecommendationException e) {
                // 어느 단계에서 실패했는지 로그로 남긴다(장소 이름만 들어가고 사용자 정보는 없다).
                log.warn("일정 생성 실패 job={} code={} ({})",id,e.getCode(),e.getMessage(),e);
                itineraries.fail(work,e.getCode(),e.getMessage());
            }
            catch(RuntimeException e) {
                log.warn("일정 생성 오류 job={}",id,e);
                itineraries.fail(work,"SCHEDULE_FAILED","일정을 만들지 못했어요. 잠시 후 다시 요청해 주세요.");
            }
        }
    }
    @Scheduled(fixedDelay=60000)
    public void cleanup() { if(ready) service.cleanup(); }
}
