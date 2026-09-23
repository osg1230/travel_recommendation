package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class RecommendationJobService {
    private final RecommendationRequestRepository requests;
    private final AiJobRepository jobs;
    private final UserRepository users;
    private final DestinationCandidateService candidates;
    private final ObjectMapper mapper;
    private final RecommendationCandidateRepository results;
    @Value("${travel.jobs.retention-hours:24}") private int retentionHours;

    @Transactional(timeout=10)
    public UUID create(ConfirmedTravelConditions conditions, String loginId, byte[] guestHash) {
        Long userId=activeUser(loginId);
        if (userId==null && (guestHash==null || guestHash.length!=32)) throw new AccessDeniedException("Owner required");
        // 클라이언트 JSON 대신 이미 서버에서 검증·확정한 세션 DTO만 받아 스냅샷으로 고정한다.
        Long requestId=null;
        if(userId!=null) requestId=requests.saveAndFlush(new RecommendationRequest(userId,conditions,conditionJson(conditions))).getId();
        AiJob job=new AiJob(userId,userId==null?guestHash:null,requestId,mapper.writeValueAsString(conditions),
            now().plusHours(Math.max(1,Math.min(168,retentionHours))));
        jobs.saveAndFlush(job);
        return job.getId();
    }

    /**
     * 저장한 일정의 원래 추천이 만료·삭제됐을 때 수정을 이어 가기 위한 추천 작업을 만든다.
     * AI 추천을 다시 하지 않고 저장한 여행지 하나만 결과로 둔다. 새 보관 기간을 받는다.
     */
    @Transactional(timeout=10)
    public UUID restoreForEdit(ConfirmedTravelConditions conditions, DestinationCandidate destination, String loginId) {
        Long userId=activeUser(loginId);
        if(userId==null) throw new AccessDeniedException("Login required");
        var result=List.of(new RankedDestination(1,destination,"저장한 일정의 여행지예요."));
        var job=AiJob.restoredRecommendation(userId,mapper.writeValueAsString(conditions),mapper.writeValueAsString(result),
            now().plusHours(Math.max(1,Math.min(168,retentionHours))));
        return jobs.saveAndFlush(job).getId();
    }

    @Transactional(readOnly=true, timeout=10)
    public RecommendationJobView view(UUID id,String loginId,byte[] guestHash) {
        AiJob job=owned(id,loginId,guestHash,"RECOMMENDATION");
        boolean completed="COMPLETED".equals(job.getStatus());
        List<DestinationCandidate> result=job.getResultJson()==null || completed?List.of():
            Arrays.asList(mapper.readValue(job.getResultJson(),DestinationCandidate[].class));
        List<RankedDestination> ranked=completed?Arrays.asList(mapper.readValue(job.getResultJson(),RankedDestination[].class)):List.of();
        return new RecommendationJobView(id,job.getStatus(),job.getMessage(),job.getErrorCode(),result,
            Set.of("QUEUED","ANALYZING_CONDITIONS","SEARCHING_CANDIDATES","RECOMMENDATION_QUEUED","RANKING_CANDIDATES").contains(job.getStatus()),
            ranked,job.getAttemptCount()<3 && ("CANDIDATES_READY".equals(job.getStatus()) || ("FAILED".equals(job.getStatus()) && job.getResultJson()!=null)),job.getAttemptCount());
    }

    @Transactional(readOnly=true, timeout=10)
    public AiJob owned(UUID id,String loginId,byte[] guestHash,String type) {
        Long userId=activeUser(loginId);
        return jobs.findById(id).filter(job->openable(job,userId,guestHash,type)).orElseThrow(this::notFound);
    }

    /**
     * owned와 같은 검사를 하되 예외 대신 빈 값을 돌려준다. 다른 트랜잭션 안에서 "열 수 있는지"만 확인할 때 쓴다.
     * 트랜잭션 메서드가 예외를 던지면 호출한 쪽에서 잡아도 바깥 트랜잭션이 롤백 전용이 되기 때문이다.
     */
    @Transactional(readOnly=true, timeout=10)
    public Optional<AiJob> findOwned(UUID id,String loginId,byte[] guestHash,String type) {
        Long userId=loginId==null?null:users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE).map(User::getId).orElse(null);
        if(loginId!=null && userId==null) return Optional.empty();
        return jobs.findById(id).filter(job->openable(job,userId,guestHash,type));
    }

    private boolean openable(AiJob job,Long userId,byte[] guestHash,String type) {
        // 비회원 작업은 만든 브라우저 세션의 토큰으로만 연다. 그 세션에서 로그인해도 토큰이 남아 있어
        // 비회원일 때 만든 초안을 이어서 보고 저장할 수 있다. 다른 세션·다른 회원은 토큰이 없어 열 수 없다.
        boolean owner=job.getUserId()!=null ? job.getUserId().equals(userId)
            : guestHash!=null && MessageDigest.isEqual(job.getGuestTokenHash(),guestHash);
        return owner && job.getExpiresAt().isAfter(now()) && type.equals(job.getJobType());
    }

    /**
     * 로그인 직후, 이 세션에서 로그인 전에 만든 비회원 추천·일정 초안을 회원 것으로 옮겨 추천 이력에 남긴다.
     * 추천은 원래 요청 시각으로 추천 요청을 만들고, 끝난 추천이면 결과도 회원 추천과 같은 방식으로 저장한다.
     * 일정 초안은 옮긴 추천 요청에 연결한다. 비회원 초안은 로그인해야 저장할 수 있으므로, 저장할 때는 이미 옮겨져
     * 저장한 일정도 같은 추천 요청과 연결된다.
     * 추천이 처리 중일 수 있어 작업 행을 잠근 뒤 옮긴다. 옮긴 추천 수를 돌려준다.
     */
    @Transactional(timeout=10)
    public int adoptGuestJobs(String loginId, byte[] guestHash) {
        Long userId=activeUser(loginId);
        if(userId==null || guestHash==null || guestHash.length!=32) return 0;
        Map<UUID,Long> requestByJob=new HashMap<>();
        int adopted=0;
        for(var found:jobs.findGuestJobs(guestHash,now())) {
            var job=jobs.lockById(found.getId()).orElse(null);
            // 잠그는 사이 다른 요청이 먼저 옮겼거나 지워졌으면 건너뛴다.
            if(job==null || job.getUserId()!=null || !MessageDigest.isEqual(job.getGuestTokenHash(),guestHash)) continue;
            if("RECOMMENDATION".equals(job.getJobType())) {
                var conditions=mapper.readValue(job.getRequestJson(),ConfirmedTravelConditions.class);
                var request=requests.saveAndFlush(new RecommendationRequest(userId,conditions,conditionJson(conditions),job.getCreatedAt()));
                if("COMPLETED".equals(job.getStatus()) && job.getResultJson()!=null)
                    for(var r:mapper.readValue(job.getResultJson(),RankedDestination[].class))
                        results.save(new RecommendationCandidate(request.getId(),r.destination().placeId(),r.rank(),r.reason(),
                            mapper.writeValueAsString(r.destination())));
                job.adopt(userId,request.getId());
                requestByJob.put(job.getId(),request.getId());
                adopted++;
            } else {
                // 일정 초안은 부모 추천을 먼저 옮겼으면 그 요청에 연결한다. 부모가 만료돼 없으면 연결 없이 옮긴다.
                var input=mapper.readValue(job.getRequestJson(),com.travel.travel_recommendation.dto.ItineraryPlan.Input.class);
                job.adopt(userId,requestByJob.get(input.recommendationId()));
            }
        }
        results.flush(); jobs.flush();
        return adopted;
    }

    @Transactional(readOnly=true)
    public List<UUID> queued() {
        return jobs.findTop5ByJobTypeAndStatusOrderByCreatedAtAsc("RECOMMENDATION","QUEUED").stream().map(AiJob::getId).toList();
    }

    @Transactional(timeout=15)
    public void prepare(UUID id) {
        AiJob job=jobs.lockById(id).orElse(null);
        if(job==null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return;
        if(!job.getExpiresAt().isAfter(now())) { job.fail("EXPIRED","요청이 만료됐어요. 조건을 확인하고 다시 요청해 주세요."); return; }
        if(job.getCreatedAt().isBefore(now().minusSeconds(60))) {
            job.fail("TIMEOUT","요청 대기 시간이 초과됐어요. 잠시 후 다시 요청해 주세요."); return;
        }
        if(job.getUserId()!=null && users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isEmpty()) {
            job.fail("ACCOUNT_UNAVAILABLE","현재 계정으로 요청을 처리할 수 없어요."); return;
        }
        job.searching();
        ConfirmedTravelConditions c=mapper.readValue(job.getRequestJson(),ConfirmedTravelConditions.class);
        var found=candidates.find(c);
        if(found.isEmpty()) {
            job.fail("NO_DESTINATIONS","이 지역에서 조회 가능한 여행지가 없어요. 시·도 이름(예: 강원, 전북)으로 고르거나 상관없음으로 선택해 주세요.");
        } else {
            // 최종 추천 테이블에는 아직 넣지 않는다. 지역 후보를 확보한 준비 단계만 기록한다.
            job.candidatesReady(mapper.writeValueAsString(found));
            // 한 번의 추천 요청 안에서 후보 조회 다음 순위·이유 생성까지 이어 간다.
            if(c.directRecommendation()) job.queueRecommendation();
        }
    }

    @Transactional(timeout=10)
    public void fail(UUID id) {
        jobs.lockById(id).filter(j -> Set.of("QUEUED","SEARCHING_CANDIDATES").contains(j.getStatus()))
            .ifPresent(j -> j.fail("LOOKUP_FAILED","여행지 후보를 불러오지 못했어요. 잠시 후 다시 요청해 주세요."));
    }
    @Transactional(timeout=10)
    public void recover() { jobs.recoverInterrupted(now()); }
    @Transactional(timeout=10)
    public void cleanup() { jobs.deleteExpiredGuests(now()); }

    /** 추천 요청의 확정 조건. 예산 미입력 안내(SFR-03-03)처럼 결과 화면에서 조건에 따라 달리 보여 줄 때 쓴다. */
    @Transactional(readOnly=true, timeout=10)
    public ConfirmedTravelConditions conditions(UUID id,String loginId,byte[] guestHash) {
        return mapper.readValue(owned(id,loginId,guestHash,"RECOMMENDATION").getRequestJson(),ConfirmedTravelConditions.class);
    }

    /** 추천에 전달한 여행 취향 이름. 결과 화면에서 추천 이유와 연결해 보여 준다(SFR-02-06). */
    @Transactional(readOnly=true, timeout=10)
    public List<String> themes(UUID id,String loginId,byte[] guestHash) {
        var conditions=mapper.readValue(owned(id,loginId,guestHash,"RECOMMENDATION").getRequestJson(),ConfirmedTravelConditions.class);
        return conditions.selectedKeywords().stream().filter(k->"THEME".equals(k.categoryCode())).map(TravelKeyword::name).toList();
    }

    private Long activeUser(String loginId) {
        if(loginId==null) return null;
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
    private String conditionJson(ConfirmedTravelConditions c) {
        Map<String,Object> json=new LinkedHashMap<>();
        json.put("schema_version",1);
        for(String category:List.of("SEASON","COMPANION","TRANSPORTATION")) {
            var selected=c.selectedKeywords().stream().filter(k->category.equals(k.categoryCode())).findFirst();
            json.put(category.toLowerCase(Locale.ROOT),selected.map(TravelKeyword::name).orElse(null));
        }
        json.put("theme",c.selectedKeywords().stream().filter(k->"THEME".equals(k.categoryCode())).map(TravelKeyword::name).toList());
        json.put("party_size",c.partySize()); json.put("include_nearby",c.includeNearby());
        json.put("selected_keywords",c.selectedKeywords().stream().map(k->{
            Map<String,Object> item=new LinkedHashMap<>();
            item.put("keyword_id",k.id()); item.put("category_code",k.categoryCode());
            item.put("name",k.name()); item.put("value",mapper.readTree(k.valueJson()));
            return item;
        }).toList());
        return mapper.writeValueAsString(json);
    }
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"요청을 찾을 수 없거나 만료됐어요."); }
}
