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
    public void enqueue(UUID id,String loginId,byte[] guestHash,int expectedAttempt) { enqueue(id,loginId,guestHash,expectedAttempt,()->{}); }

    /**
     * beforeQueue는 실제로 대기열에 넣기 직전에만 부른다(화면의 AI 요청 횟수 세기).
     * 이미 넣었거나 이전 버튼의 재전송이라 아무것도 하지 않는 요청은 횟수를 쓰지 않는다. beforeQueue가 예외를 던지면 넣지 않는다.
     */
    @Transactional(timeout=10)
    public void enqueue(UUID id,String loginId,byte[] guestHash,int expectedAttempt,Runnable beforeQueue) {
        var job=jobs.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        jobService.view(id,loginId,guestHash); // 모든 제출도 조회와 같은 소유권·만료 검사를 거친다.
        if(Set.of("RECOMMENDATION_QUEUED","RANKING_CANDIDATES","COMPLETED").contains(job.getStatus())) return;
        if(expectedAttempt!=job.getAttemptCount()) return; // 이전 버튼의 재전송으로 유료 재시도를 만들지 않는다.
        if(job.getAttemptCount()>=3) throw new ResponseStatusException(HttpStatus.CONFLICT,"추천 시도 횟수를 모두 사용했어요.");
        if(!("CANDIDATES_READY".equals(job.getStatus()) || ("FAILED".equals(job.getStatus()) && job.getResultJson()!=null)))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"먼저 여행지 후보를 조회해 주세요.");
        if(!client.configured()) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"추천 연결 설정을 확인해 주세요.");
        beforeQueue.run();
        job.queueRecommendation();
    }

    @Transactional(readOnly=true)
    public List<UUID> queued() {
        return jobs.findTop5ByStatusOrderByCreatedAtAsc("RECOMMENDATION_QUEUED").stream().map(AiJob::getId).toList();
    }

    /** 순위 만들기 처리 제한. AI 응답(최대 25초, 이유를 다시 받을 때 한 번 더)과 축제 조회가 들어가므로 실행기가 시작한 시각부터 90초다. */
    static final Duration RANKING_LIMIT=Duration.ofSeconds(90);
    /** AI 이유 대신 여행지 소개를 넣은 추천 이유의 앞머리. 결과 화면과 추천 이력에서 AI가 쓴 이유가 아님을 알린다. */
    public static final String NO_AI_REASON="AI 추천 이유 없음";

    @Transactional(timeout=10)
    public Work claim(UUID id) {
        var job=jobs.lockById(id).orElse(null);
        if(job==null || !"RECOMMENDATION_QUEUED".equals(job.getStatus())) return null;
        if(!validOwnerAndTime(job,null)) return null;
        // 기다리기 시작한 시각(queueRecommendation)부터 대기 제한을 잰다. 앞선 요청이 많아 오래 기다렸으면 처리하지 않는다.
        if(job.getStartedAt()==null || job.getStartedAt().isBefore(KoreaTime.now().minus(RecommendationJobService.WAIT_LIMIT))) {
            job.failRecommendation("TIMEOUT",RecommendationJobService.WAIT_MESSAGE); return null;
        }
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
        if(!validOwnerAndTime(job,RANKING_LIMIT)) return;
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

    /**
     * AI 순위를 더 받을 수 없을 때(시도를 다 썼거나 AI 한도·연결 설정 문제) 화면에 보이던 후보로 추천을 마친다.
     * 순위는 서버가 조건에 맞춰 고른 후보 순서이고, 이유 자리에는 공식 관광 사이트에서 확인해 저장한 여행지 소개를
     * "AI 추천 이유 없음" 표시와 함께 넣는다. AI를 부르지 않으며, 두 번 눌러도 한 번만 마친다.
     */
    @Transactional(timeout=10)
    public void continueWithoutAi(UUID id,String loginId,byte[] guestHash) {
        var job=jobs.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        var view=jobService.view(id,loginId,guestHash); // 조회와 같은 소유권·만료 검사
        if("COMPLETED".equals(job.getStatus())) return;
        if(!view.canContinueWithoutAi())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"지금은 이 후보로 계속할 수 없어요. 화면을 새로 고쳐 상태를 확인해 주세요.");
        var pool=pool(job);
        // 보여 준 후보가 지금도 같은 정보로 쓸 수 있는 여행지인지 다시 본다(사용 중지·정보 변경이면 계속하지 않는다).
        if(!currentPlacesMatch(mapper.readValue(job.getRequestJson(),ConfirmedTravelConditions.class),pool))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"여행지 정보가 변경됐어요. 조건을 확인하고 다시 요청해 주세요.");
        List<RankedDestination> ranked=new ArrayList<>();
        for(var destination:pool) ranked.add(new RankedDestination(ranked.size()+1,destination,reasonWithoutAi(destination)));
        // 회원이면 추천 이력에도 남긴다(complete와 같은 방식). 후보 행과 COMPLETED는 같은 트랜잭션으로 반영된다.
        if(job.getUserId()!=null) {
            var request=requests.findById(job.getRequestId()).orElseThrow(()->new IllegalStateException("Request missing"));
            if(!job.getUserId().equals(request.getUserId())) throw new IllegalStateException("Owner mismatch");
            for(var r:ranked) results.save(new RecommendationCandidate(request.getId(),r.destination().placeId(),
                r.rank(),r.reason(),mapper.writeValueAsString(r.destination())));
            results.flush();
        }
        job.completeWithoutAiReason(mapper.writeValueAsString(ranked));
        jobs.flush();
    }

    /** AI 이유 대신 쓰는 이유. 추천 이유 칸(최대 2,000자)에 맞춘다. */
    static String reasonWithoutAi(DestinationCandidate destination) {
        String about=destination.description()==null || destination.description().isBlank()
            ?"이 여행지의 소개가 아직 없어요. 공식 관광 정보에서 확인해 주세요.":destination.description().strip();
        String reason=NO_AI_REASON+" · "+about;
        return reason.length()>2000?reason.substring(0,2000):reason;
    }

    /** claim이 실패한 작업을 끝낸다. 저장된 요청을 읽지 못하는 작업은 다시 해도 같아서, 남겨 두면 1초마다 같은 오류가 난다. */
    @Transactional(timeout=10)
    public void failClaim(UUID id) {
        jobs.lockById(id).filter(j->"RECOMMENDATION_QUEUED".equals(j.getStatus()))
            .ifPresent(j->j.failRecommendation("RECOMMENDATION_FAILED","요청을 처리하지 못했어요. 조건을 확인하고 다시 요청해 주세요."));
    }
    @Transactional(timeout=10)
    public void fail(Work work,String code,String safeMessage) {
        jobs.lockById(work.jobId()).filter(j->sameAttempt(j,work)).ifPresent(j->j.failRecommendation(code,safeMessage));
    }
    private boolean sameAttempt(AiJob job,Work work) {
        return job!=null && "RANKING_CANDIDATES".equals(job.getStatus()) && job.getAttemptCount()==work.attempt();
    }
    /** processLimit이 있으면 실행기가 순위 만들기를 시작한 시각(ranking)부터 그 시간 안에 끝났는지도 본다. */
    private boolean validOwnerAndTime(AiJob job,Duration processLimit) {
        LocalDateTime now=KoreaTime.now();
        boolean slow=processLimit!=null && (job.getStartedAt()==null || job.getStartedAt().isBefore(now.minus(processLimit)));
        if(!job.getExpiresAt().isAfter(now) || slow) {
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
