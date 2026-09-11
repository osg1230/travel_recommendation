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
    private volatile boolean ready;

    @EventListener(ApplicationReadyEvent.class)
    public void start() { service.recover(); service.cleanup(); ready=true; }

    // 단일 서버의 단일 실행기로 DB 조회만 수행한다. 작업 생성 응답은 이 처리를 기다리지 않는다.
    @Scheduled(fixedDelay=1000)
    public void runQueued() {
        if(!ready) return;
        for(UUID id:service.queued()) {
            try { service.prepare(id); }
            catch(RuntimeException e) { service.fail(id); }
        }
    }
    @Scheduled(fixedDelay=60000)
    public void cleanup() { if(ready) service.cleanup(); }
}
