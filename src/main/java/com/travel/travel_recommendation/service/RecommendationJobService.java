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
    @Value("${travel.jobs.retention-hours:24}") private int retentionHours;

    /**
     * 실행기가 처리를 시작하기 전까지 기다릴 수 있는 시간(후보 찾기·순위 만들기 대기 모두).
     * 실행기는 요청을 차례로 처리하므로 앞선 요청이 많으면 기다리게 된다. 그 때문에 바로 실패하지 않게 처리 제한과 따로 넉넉히 둔다.
     */
    public static final Duration WAIT_LIMIT=Duration.ofMinutes(5);
    /** 후보 찾기 처리 제한. 지역 제안·목적 시설 검색·후보 고르기(AI 호출 포함)가 들어가므로 실행기가 시작한 시각부터 2분이다. */
    static final Duration SEARCH_LIMIT=Duration.ofMinutes(2);
    /** 결과 화면이 상태를 확인하는 시간(초). 대기 제한과 후보 찾기 처리 제한을 더한 만큼 기다린다. */
    public static final long POLL_SECONDS=WAIT_LIMIT.plus(SEARCH_LIMIT).toSeconds();
    public static final String WAIT_MESSAGE="요청이 많아 처리하지 못했어요. 다시 요청해 주세요.";
    /** 대기 중이라고 알리기까지의 시간. 실행기는 1초마다 확인하므로 몇 초가 지나도 시작하지 않았으면 앞선 요청을 처리하는 중이다. */
    private static final Duration WAITING_NOTICE_AFTER=Duration.ofSeconds(3);
    /** 같은 조건으로 다시 시도하면 될 수 있는 일시적인 실패. 지역에 맞는 여행지가 없거나 만료된 요청은 다시 해도 같아 뺀다. */
    static final Set<String> RETRY_CODES=Set.of("TIMEOUT","LOOKUP_FAILED","SERVER_RESTART","AI_TIMEOUT","AI_UNAVAILABLE","AI_RATE_LIMIT");
    /** 다시 시도해도 AI 순위를 받을 수 없는 실패. 이때는 AI 이유 없이 보이는 후보로 계속할 수 있게 한다. */
    static final Set<String> AI_STOPPED_CODES=Set.of("AI_QUOTA","AI_AUTH","AI_NOT_CONFIGURED");

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
        boolean failed="FAILED".equals(job.getStatus());
        boolean canRecommend=job.getAttemptCount()<3 && ("CANDIDATES_READY".equals(job.getStatus()) || (failed && job.getResultJson()!=null));
        // 후보는 있는데 AI 순위를 더 받을 수 없으면(시도를 다 썼거나 AI 한도·설정 문제) 보이는 후보로 계속할 수 있다.
        boolean canContinue=failed && job.getResultJson()!=null
            && (job.getAttemptCount()>=3 || AI_STOPPED_CODES.contains(job.getErrorCode()));
        // '추천 다시 받기'를 쓸 수 없는 일시적인 실패만 같은 조건으로 새로 요청하게 한다.
        boolean canRetry=failed && !canRecommend && RETRY_CODES.contains(job.getErrorCode());
        return new RecommendationJobView(id,job.getStatus(),waiting(job)?"앞선 요청을 처리하고 있어요. 차례가 되면 바로 시작해요.":job.getMessage(),
            job.getErrorCode(),result,
            Set.of("QUEUED","ANALYZING_CONDITIONS","SEARCHING_CANDIDATES","RECOMMENDATION_QUEUED","RANKING_CANDIDATES").contains(job.getStatus()),
            ranked,canRecommend,job.getAttemptCount(),canContinue,canRetry);
    }

    /** 실행기가 아직 이 요청을 시작하지 않은 채 몇 초가 지났는지. 그동안은 앞선 요청을 처리하고 있다. */
    private boolean waiting(AiJob job) {
        LocalDateTime noticeAfter=now().minus(WAITING_NOTICE_AFTER);
        if("QUEUED".equals(job.getStatus())) return job.getStartedAt()==null && job.getCreatedAt().isBefore(noticeAfter);
        // 순위 만들기를 기다리는 동안 startedAt은 기다리기 시작한 시각이다(AiJob.queueRecommendation).
        return "RECOMMENDATION_QUEUED".equals(job.getStatus()) && job.getStartedAt()!=null && job.getStartedAt().isBefore(noticeAfter);
    }

    /**
     * 일시적인 이유(시간 초과·조회 실패·서버 재시작 등)로 실패한 요청을 그 요청의 조건(requestJson) 그대로 다시 요청한다.
     * 새 작업을 만들고 실패한 작업은 그대로 둔다. 다른 화면에서 바꾼 조건이 섞이지 않는다.
     */
    @Transactional(timeout=10)
    public UUID retry(UUID id,String loginId,byte[] guestHash) {
        var view=view(id,loginId,guestHash); // 조회와 같은 소유권·만료 검사
        if(!view.canRetry()) throw new ResponseStatusException(HttpStatus.CONFLICT,"이 요청은 같은 조건으로 다시 시도할 수 없어요. 조건을 확인하고 새로 요청해 주세요.");
        var conditions=mapper.readValue(owned(id,loginId,guestHash,"RECOMMENDATION").getRequestJson(),ConfirmedTravelConditions.class);
        return create(conditions,loginId,guestHash);
    }

    /**
     * 작업을 만들기 전에 지역 조건에 맞는 여행지가 있는지 확인한다. 있으면 null, 없으면 지역 칸에 보여 줄 안내다.
     * 목록에 없는 세부 지역(예: "제주 우도")이면 첫 단어("제주")로 찾은 여행지를 함께 제안한다. 지역을 억지로 넓혀 추천하지는 않는다.
     */
    @Transactional(readOnly=true, timeout=10)
    public String regionProblem(ConfirmedTravelConditions c) {
        if(!candidates.matching(c).isEmpty()) return null;
        String region=c.region()==null?"":c.region().strip();
        if(region.isEmpty()) return "제외한 지역을 빼면 추천할 수 있는 여행지가 없어요. 제외할 지역을 줄여 주세요.";
        String first=region.split("\\s+")[0];
        var found=byFirstWord(c);
        if(found.isEmpty())
            return "'"+region+"'에 맞는 여행지를 찾지 못했어요. 시·도 이름(예: 강원, 전북)이나 시·군 이름으로 적거나, 지역을 비워 두세요.";
        return "'"+region+"'에 맞는 여행지를 찾지 못했어요. 대신 '"+first+"' 지역의 "
            +String.join(", ",found.stream().map(DestinationCandidate::name).toList())+"에서 찾을 수 있어요. 지역 칸을 '"+first+"'처럼 고쳐 주세요.";
    }

    /**
     * 지역 칸의 이름으로도, 그 첫 단어로도 여행지를 찾지 못하는지(예: 산 이름 '속리산', 아직 여행지가 아닌 시·군 '보은').
     * 이런 이름은 지역 대신 원하는 여행으로 옮기면, 추천 작업에서 AI가 맞는 시·군을 제안하고 관광공사 등록 장소로 확인해 여행지로 더한다
     * (RegionSuggestionService). 지역을 비웠거나 찾을 수 있으면 false다(2026-10-31).
     */
    @Transactional(readOnly=true, timeout=10)
    public boolean regionUnknown(ConfirmedTravelConditions c) {
        String region=c.region()==null?"":c.region().strip();
        return !region.isEmpty() && candidates.matching(c).isEmpty() && byFirstWord(c).isEmpty();
    }

    /** 세부 지역(예: "제주 우도")의 첫 단어로 찾은 여행지. 한 단어 지역이면 빈 목록이다. */
    private List<DestinationCandidate> byFirstWord(ConfirmedTravelConditions c) {
        String region=c.region()==null?"":c.region().strip();
        String first=region.split("\\s+")[0];
        return first.equals(region)?List.of():candidates.find(first,List.of(),List.of(),List.of(),c.excludedRegions());
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

    @Transactional(readOnly=true)
    public List<UUID> queued() {
        return jobs.findTop5ByJobTypeAndStatusOrderByCreatedAtAsc("RECOMMENDATION","QUEUED").stream().map(AiJob::getId).toList();
    }

    /**
     * 실행기가 이 요청의 후보 찾기를 시작할 때 부른다. 처리할 수 있으면 시작 시각을 남기고 true를 돌려준다.
     * 시작하기 전에 대기 제한을 넘겼으면 요청이 많아 처리하지 못했다고 끝내고 false를 돌려준다.
     */
    @Transactional(timeout=10)
    public boolean begin(UUID id) {
        AiJob job=jobs.lockById(id).orElse(null);
        if(job==null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return false;
        if(waitedTooLong(job)) { job.fail("TIMEOUT",WAIT_MESSAGE); return false; }
        job.beginSearch();
        return true;
    }
    // 아직 시작하지 않은 요청은 만든 시각부터 대기 제한을 잰다.
    static boolean waitedTooLong(AiJob job) {
        return job.getStartedAt()==null && job.getCreatedAt().isBefore(KoreaTime.now().minus(WAIT_LIMIT));
    }
    /** 어차피 버려질 대기 작업인지. 일정 작업을 시작하기 전 단계(AI 순서·주변 음식점)가 이런 작업에 유료·한도 호출을 쓰지 않게 한다. */
    static boolean abandoned(AiJob job) {
        return waitedTooLong(job) || !job.getExpiresAt().isAfter(KoreaTime.now());
    }

    @Transactional(timeout=15)
    public void prepare(UUID id) {
        AiJob job=jobs.lockById(id).orElse(null);
        if(job==null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return;
        if(!job.getExpiresAt().isAfter(now())) { job.fail("EXPIRED","요청이 만료됐어요. 조건을 확인하고 다시 요청해 주세요."); return; }
        if(waitedTooLong(job)) { job.fail("TIMEOUT",WAIT_MESSAGE); return; }
        // 처리 시간 제한은 실행기가 시작한 시각(begin)부터 잰다. 앞선 요청을 기다린 시간은 넣지 않는다.
        if(job.getStartedAt()!=null && job.getStartedAt().isBefore(now().minus(SEARCH_LIMIT))) {
            job.fail("TIMEOUT","여행지 후보를 찾는 데 시간이 너무 오래 걸렸어요. 잠시 후 다시 요청해 주세요."); return;
        }
        if(job.getUserId()!=null && users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isEmpty()) {
            job.fail("ACCOUNT_UNAVAILABLE","현재 계정으로 요청을 처리할 수 없어요."); return;
        }
        job.searching();
        ConfirmedTravelConditions c=mapper.readValue(job.getRequestJson(),ConfirmedTravelConditions.class);
        var found=candidates.find(c,job.getUserId());
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
    private LocalDateTime now() { return KoreaTime.now(); }
    private ResponseStatusException notFound() { return new ResponseStatusException(HttpStatus.NOT_FOUND,"요청을 찾을 수 없거나 만료됐어요."); }
}
