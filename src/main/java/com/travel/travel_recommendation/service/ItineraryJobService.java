package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class ItineraryJobService {
    private final RecommendationJobService access;
    private final PlaceCandidateService candidates;
    private final AiJobRepository jobs;
    private final UserRepository users;
    private final PlaceRepository places;
    private final ItineraryGenerator generator;
    private final ObjectMapper mapper;
    public record Work(UUID id,Input input) {}

    @Transactional(readOnly=true,timeout=10)
    public Input options(UUID parentId,long destinationId,String login,byte[] hash) {
        var pool=candidates.find(parentId,destinationId,login,hash);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        var conditions=mapper.readValue(parent.getRequestJson(),ConfirmedTravelConditions.class);
        int days=conditions.durationDays()==null?1:Math.max(1,Math.min(7,conditions.durationDays()));
        // 후보가 늘어도 전부 방문시키지 않는다. 기간당 최대 2곳, 전체 8곳으로 조회 비용·체류량을 제한한다.
        pool=new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),pool.accommodationNeeded(),
            pool.attractions().stream().limit(Math.min(8,days*2)).toList(),pool.accommodations(),pool.restaurants());
        var transport=confirmedTransport(conditions);
        return new Input(parentId,pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),
            transport==null?KakaoMapClient.Transport.CAR:transport,90);
    }

    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes) {
        var current=options(parentId,destinationId,login,hash);
        var fixed=confirmedTransport(current.conditions());
        if(fixed!=null && fixed!=transport) throw new ResponseStatusException(HttpStatus.CONFLICT,"확정한 이동수단과 달라요. 여행 조건에서 먼저 수정해 주세요.");
        var input=new Input(parentId,current.places(),current.conditions(),start,end,transport,visitMinutes);
        generator.checkInput(input);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }

    @Transactional(readOnly=true,timeout=10)
    public View view(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        return new View(id,job.getStatus(),job.getMessage(),Set.of("QUEUED","GENERATING_SCHEDULE","VALIDATING_SCHEDULE").contains(job.getStatus()),
            mapper.readValue(job.getRequestJson(),Input.class),job.getResultJson()==null?null:mapper.readValue(job.getResultJson(),Result.class));
    }
    @Transactional(readOnly=true)
    public List<UUID> queued() { return jobs.findTop5ByJobTypeAndStatusOrderByCreatedAtAsc("ITINERARY","QUEUED").stream().map(AiJob::getId).toList(); }

    @Transactional(timeout=10)
    public Work claim(UUID id) {
        var job=jobs.lockById(id).orElse(null);
        if(job==null || !"ITINERARY".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        if(!available(job) || job.getCreatedAt().isBefore(now().minusMinutes(2))) {
            job.fail("SCHEDULE_UNAVAILABLE","요청이 만료됐거나 처리할 수 없어요. 조건을 확인해 다시 요청해 주세요."); return null;
        }
        job.generatingSchedule(); return new Work(id,mapper.readValue(job.getRequestJson(),Input.class));
    }
    // 외부 지도 호출 동안 트랜잭션이나 DB 잠금을 유지하지 않는다.
    public Result generate(Work work) { return generator.generate(work.input()); }

    @Transactional(timeout=10)
    public void complete(Work work,Result result) {
        var job=jobs.lockById(work.id()).orElse(null);
        if(job==null || !"GENERATING_SCHEDULE".equals(job.getStatus()) || !"ITINERARY".equals(job.getJobType())) return;
        if(!available(job) || job.getStartedAt().isBefore(now().minusMinutes(2))) { job.fail("SCHEDULE_TIMEOUT","요청이 만료되거나 처리 시간이 초과됐어요. 다시 요청해 주세요."); return; }
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        if(!input.equals(work.input()) || !unchanged(input)) { job.fail("PLACES_CHANGED","장소 정보가 변경됐어요. 장소 목록에서 다시 요청해 주세요."); return; }
        job.validatingSchedule(); generator.validate(input,result);
        job.completeSchedule(mapper.writeValueAsString(result)); jobs.flush();
    }
    @Transactional(timeout=10)
    public void fail(Work work,String code,String message) {
        jobs.lockById(work.id()).filter(j->"ITINERARY".equals(j.getJobType()) && "GENERATING_SCHEDULE".equals(j.getStatus()))
            .ifPresent(j->j.fail(code,message));
    }
    private boolean unchanged(Input input) {
        var pool=new ArrayList<>(input.places().attractions()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var destination=input.places().destination();
        if(places.findById(destination.placeId()).filter(p->p.isActive() && "DESTINATION".equals(p.getPlaceType())
                && destination.name().equals(p.getName()) && destination.region().equals(p.getRegion())).isEmpty()) return false;
        for(var candidate:pool) {
            String type=input.places().attractions().contains(candidate)?"ATTRACTION":input.places().restaurants().contains(candidate)?"RESTAURANT":"ACCOMMODATION";
            if(places.findById(candidate.placeId()).filter(p->p.isActive() && type.equals(p.getPlaceType())
                && destination.region().equals(p.getRegion()) && candidate.name().equals(p.getName())
                && candidate.address().equals(p.getAddress()) && candidate.sourceUrl().equals(p.getSourceUrl())
                && candidate.verifiedAt().equals(p.getVerifiedAt())
                && (candidate.longitude()==null || (p.getLongitude()!=null && Double.compare(candidate.longitude(),p.getLongitude().doubleValue())==0))
                && (candidate.latitude()==null || (p.getLatitude()!=null && Double.compare(candidate.latitude(),p.getLatitude().doubleValue())==0))).isEmpty()) return false;
        }
        return true;
    }
    private boolean available(AiJob job) {
        return job.getExpiresAt().isAfter(now()) && (job.getUserId()==null || users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isPresent());
    }
    private KakaoMapClient.Transport confirmedTransport(ConfirmedTravelConditions conditions) {
        for(var keyword:conditions.selectedKeywords()) if("TRANSPORTATION".equals(keyword.categoryCode())) {
            String value=mapper.readTree(keyword.valueJson()).path("value").asText();
            if("CAR".equals(value)) return KakaoMapClient.Transport.CAR;
            if("PUBLIC_TRANSIT".equals(value)) return KakaoMapClient.Transport.PUBLIC_TRANSIT;
        }
        return null;
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
