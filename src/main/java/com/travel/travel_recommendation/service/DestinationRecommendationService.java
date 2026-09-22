package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
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
public class DestinationRecommendationService {
    private final AiJobRepository jobs;
    private final UserRepository users;
    private final RecommendationRequestRepository requests;
    private final RecommendationCandidateRepository results;
    private final RecommendationJobService jobService;
    private final DestinationCandidateService places;
    private final OpenAiRecommendationClient client;
    private final ObjectMapper mapper;

    public record Work(UUID jobId,int attempt,ConfirmedTravelConditions conditions,List<DestinationCandidate> candidates) {}

    @Transactional(timeout=10)
    public void enqueue(UUID id,String loginId,byte[] guestHash,int expectedAttempt) {
        var job=jobs.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        jobService.view(id,loginId,guestHash); // 모든 제출도 조회와 같은 소유권·만료 검사를 거친다.
        if(Set.of("RECOMMENDATION_QUEUED","RANKING_CANDIDATES","COMPLETED").contains(job.getStatus())) return;
        if(expectedAttempt!=job.getAttemptCount()) return; // 이전 버튼의 재전송으로 유료 재시도를 만들지 않는다.
        if(job.getAttemptCount()>=3) throw new ResponseStatusException(HttpStatus.CONFLICT,"추천 시도 횟수를 모두 사용했어요.");
        if(!("CANDIDATES_READY".equals(job.getStatus()) || ("FAILED".equals(job.getStatus()) && job.getResultJson()!=null)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"먼저 여행지 후보를 조회해 주세요.");
        if(!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"추천 연결 설정을 확인해 주세요.");
        job.queueRecommendation();
    }

    @Transactional(readOnly=true)
    public List<UUID> queued() {
        return jobs.findTop5ByStatusOrderByCreatedAtAsc("RECOMMENDATION_QUEUED").stream().map(AiJob::getId).toList();
    }

    @Transactional(timeout=10)
    public Work claim(UUID id) {
        var job=jobs.lockById(id).orElse(null);
        if(job==null || !"RECOMMENDATION_QUEUED".equals(job.getStatus())) return null;
        if(!validOwnerAndTime(job)) return null;
        if(job.getAttemptCount()>=3) { job.failRecommendation("ATTEMPTS_EXHAUSTED","추천 시도 횟수를 모두 사용했어요.");return null; }
        var conditions=mapper.readValue(job.getRequestJson(),ConfirmedTravelConditions.class);
        var pool=pool(job);
        if(!currentPlacesMatch(conditions,pool)) { job.failRecommendation("CANDIDATES_CHANGED","여행지 정보가 변경됐어요. 조건에서 후보를 다시 조회해 주세요."); return null; }
        job.ranking();
        return new Work(id,job.getAttemptCount(),conditions,pool);
    }

    // 이 메서드에는 트랜잭션을 두지 않는다. API를 기다리는 동안 DB 연결·행 잠금을 점유하지 않는다.
    public List<RankedDestination> generate(Work work) { return client.recommend(work.conditions(),work.candidates()); }

    @Transactional(timeout=10)
    public void complete(Work work,List<RankedDestination> ranked) {
        var job=jobs.lockById(work.jobId()).orElse(null);
        if(!sameAttempt(job,work)) return;
        if(!validOwnerAndTime(job)) return;
        if(!currentPlacesMatch(work.conditions(),work.candidates())) {
            job.failRecommendation("CANDIDATES_CHANGED","여행지 정보가 변경됐어요. 후보를 다시 조회해 주세요.");return;
        }
        // 저장 직전에도 ID·개수·중복·이유를 검증하고 명칭·출처는 DB에서 얻은 스냅샷으로 고정한다.
        var validated=client.validate(mapper.valueToTree(Map.of("recommendations",ranked.stream().map(r->
            Map.of("placeId",r.destination().placeId(),"reason",r.reason())).toList())),work.candidates());
        if(job.getUserId()!=null) {
            var request=requests.findById(job.getRequestId()).orElseThrow(()->new IllegalStateException("Request missing"));
            if(!job.getUserId().equals(request.getUserId())) throw new IllegalStateException("Owner mismatch");
            for(var r:validated) results.save(new RecommendationCandidate(request.getId(),r.destination().placeId(),
                r.rank(),r.reason(),mapper.writeValueAsString(r.destination())));
            results.flush();
        }
        job.completeRecommendation(mapper.writeValueAsString(validated));
        // 후보 행과 COMPLETED는 같은 트랜잭션으로 함께 반영된다.
        jobs.flush();
    }

    @Transactional(timeout=10)
    public void fail(Work work,String code,String safeMessage) {
        jobs.lockById(work.jobId()).filter(j->sameAttempt(j,work)).ifPresent(j->j.failRecommendation(code,safeMessage));
    }
    private boolean sameAttempt(AiJob job,Work work) {
        return job!=null && "RANKING_CANDIDATES".equals(job.getStatus()) && job.getAttemptCount()==work.attempt();
    }
    private boolean validOwnerAndTime(AiJob job) {
        LocalDateTime now=LocalDateTime.now(ZoneOffset.UTC);
        if(!job.getExpiresAt().isAfter(now) || job.getStartedAt()==null || job.getStartedAt().isBefore(now.minusSeconds(60))) {
            job.failRecommendation("RECOMMENDATION_TIMEOUT","추천 처리 시간이 초과되거나 요청이 만료됐어요. 다시 시도해 주세요.");return false;
        }
        if(job.getUserId()!=null && users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isEmpty()) {
            job.failRecommendation("ACCOUNT_UNAVAILABLE","현재 계정으로 추천을 처리할 수 없어요.");return false;
        }
        return true;
    }
    private List<DestinationCandidate> pool(AiJob job) { return List.of(mapper.readValue(job.getResultJson(),DestinationCandidate[].class)); }
    private boolean currentPlacesMatch(ConfirmedTravelConditions c,List<DestinationCandidate> pool) {
        return !pool.isEmpty() && pool.size()<=5 && places.find(c.region(),c.themeNames()).equals(pool);
    }
}
