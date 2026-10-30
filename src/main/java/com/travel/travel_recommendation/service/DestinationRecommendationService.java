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
    private final DestinationFestivalService festivals;
    private final DestinationProfileService profiles;
    private final SimilarMemberService similarMembers;
    private final ObjectMapper mapper;

    /** members는 후보 여행지별로 비슷한 조건으로 여행한 다른 회원의 기록이다. 기록이 없으면 비어 있다. */
    public record Work(UUID jobId,int attempt,ConfirmedTravelConditions conditions,List<DestinationCandidate> candidates,
                       Map<Long,MemberStats> members) {
        public Work(UUID jobId,int attempt,ConfirmedTravelConditions conditions,List<DestinationCandidate> candidates) {
            this(jobId,attempt,conditions,candidates,Map.of());
        }
    }

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
        // 순위 AI에 함께 넘길 비슷한 조건 회원 기록. 본인 기록은 뺀다.
        var members=similarMembers.destinations(conditions,job.getUserId(),pool.stream().map(DestinationCandidate::placeId).toList());
        return new Work(id,job.getAttemptCount(),conditions,pool,members);
    }

    // 이 메서드에는 트랜잭션을 두지 않는다. API를 기다리는 동안 DB 연결·행 잠금을 점유하지 않는다.
    // 후보 여행지의 축제를 먼저 찾아 추천 이유에 반영한다. 사용자가 축제를 고르지 않아도 여행 시기의 축제를 알려 주기 위해서다.
    // 후보마다 "할 수 있는 것" 근거(관광공사 등록 장소)도 함께 넘겨, 추천 이유가 확인을 미루지 않고 근거 장소로 맞는 점을 쓰게 한다.
    // 비슷한 조건으로 여행한 회원 기록은 순위에 참고하도록 넘기고, 결과에도 서버가 센 수를 그대로 붙인다.
    public List<RankedDestination> generate(Work work) {
        return client.recommend(work.conditions(),work.candidates(),festivals.find(work.conditions(),work.candidates()),evidence(work),
            work.members());
    }

    /** 이유에 쓰는 근거 분류 수와 분류마다의 대표 이름 수. 원하는 여행의 분류(최대 6개)는 모두 담긴다. */
    static final int EVIDENCE_CATEGORIES=6, EVIDENCE_NAMES=3;

    /**
     * 후보 여행지마다 원하는 여행의 분류(지역 제안), 없으면 고른 여행 취향의 분류에서 장소가 많은 근거를 고른다.
     * 취향도 없으면 장소가 많은 분류를 고른다. 보관한 목록만 읽는다(관광공사를 새로 부르지 않음).
     */
    private Map<Long,List<DestinationProfile.Evidence>> evidence(Work work) {
        var c=work.conditions();
        var codes=!c.wishCategories().isEmpty()?c.wishCategories():WishCategories.codesFor(c.themeNames());
        Map<Long,List<DestinationProfile.Evidence>> result=new HashMap<>();
        profiles.saved(work.candidates().stream().map(DestinationCandidate::placeId).toList())
            .forEach((id,profile)->result.put(id,DestinationProfile.evidence(profile,codes,EVIDENCE_CATEGORIES,EVIDENCE_NAMES)));
        return result;
    }

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
            Map.of("placeId",r.destination().placeId(),"reason",r.reason(),"places",r.centerPlaces())).toList())),work.candidates())
            // 축제는 서버가 관광공사에서 찾은 값이고 제안 방향은 추천 때 이미 목록과 대조한 값이라 검증한 결과에 그대로 다시 붙인다.
            // 회원 기록도 서버가 센 값이라 그대로 붙인다.
            .stream().map(v->{ var source=ranked.stream().filter(r->r.destination().placeId().equals(v.destination().placeId())).findFirst();
                return v.withFestivals(source.map(RankedDestination::festivals).orElse(List.of()))
                    .withFestivalPlaces(source.map(RankedDestination::festivalPlaceIds).orElse(Map.of()))
                    .withDirection(source.map(RankedDestination::direction).orElse(null))
                    .withMembers(source.map(RankedDestination::members).orElse(null)); }).toList();
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
        LocalDateTime now=KoreaTime.now();
        if(!job.getExpiresAt().isAfter(now) || job.getStartedAt()==null || job.getStartedAt().isBefore(now.minusSeconds(60))) {
            job.failRecommendation("RECOMMENDATION_TIMEOUT","추천 처리 시간이 초과되거나 요청이 만료됐어요. 다시 시도해 주세요.");return false;
        }
        if(job.getUserId()!=null && users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isEmpty()) {
            job.failRecommendation("ACCOUNT_UNAVAILABLE","현재 계정으로 추천을 처리할 수 없어요.");return false;
        }
        return true;
    }
    private List<DestinationCandidate> pool(AiJob job) { return List.of(mapper.readValue(job.getResultJson(),DestinationCandidate[].class)); }
    // 준비한 후보가 지금도 같은 정보로 쓸 수 있는 여행지인지 본다(사용 중지·정보 변경이면 실패).
    // 다른 추천이 여행지를 새로 추가해도(RegionSuggestionService) 이 작업의 후보는 그대로이므로 실패로 보지 않는다.
    private boolean currentPlacesMatch(ConfirmedTravelConditions c,List<DestinationCandidate> pool) {
        return !pool.isEmpty() && pool.size()<=5 && places.usable().containsAll(pool);
    }
}
