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
    private final RecommendationJobService service;
    private final DestinationRecommendationService recommendations;
    private final ItineraryJobService itineraries;
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void start() { service.recover(); service.cleanup(); ready=true; }

    // 단일 서버의 단일 실행기로 후보 조회와 추천을 순차 처리한다. 작업 생성 응답은 이 처리를 기다리지 않는다.
    @Scheduled(fixedDelay=1000)
    public void runQueued() {
        if(!ready) return;
        for(UUID id:service.queued()) {
            try { service.prepare(id); }
            catch(RuntimeException e) { service.fail(id); }
        }
        for(UUID id:recommendations.queued()) {
            var work=recommendations.claim(id);
            if(work==null) continue;
            try { recommendations.complete(work,recommendations.generate(work)); }
            catch(RecommendationException e) { recommendations.fail(work,e.getCode(),e.getMessage()); }
            catch(RuntimeException e) { recommendations.fail(work,"RECOMMENDATION_FAILED","추천 결과를 저장하지 못했어요. 잠시 후 다시 시도해 주세요."); }
        }
        for(UUID id:itineraries.queued()) {
            var work=itineraries.claim(id);
            if(work==null) continue;
            try { itineraries.complete(work,itineraries.generate(work)); }
            catch(RecommendationException e) { itineraries.fail(work,e.getCode(),e.getMessage()); }
            catch(RuntimeException e) { itineraries.fail(work,"SCHEDULE_FAILED","일정을 만들지 못했어요. 잠시 후 다시 요청해 주세요."); }
        }
    }
    @Scheduled(fixedDelay=60000)
    public void cleanup() { if(ready) service.cleanup(); }
}
