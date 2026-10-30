package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.AiRequestLimiter;
import com.travel.travel_recommendation.service.RecommendationJobService;
import com.travel.travel_recommendation.service.BudgetGuide;
import com.travel.travel_recommendation.service.BudgetGuideService;
import com.travel.travel_recommendation.service.DestinationRecommendationService;
import com.travel.travel_recommendation.service.PlacePhotoDetails;
import com.travel.travel_recommendation.service.PlacePhotoService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static com.travel.travel_recommendation.controller.TravelConditionReviewController.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/recommendations")
public class RecommendationJobController {
    private final ObjectProvider<RecommendationJobService> services;
    private final ObjectProvider<DestinationRecommendationService> recommendations;
    private final ObjectProvider<BudgetGuideService> budgetGuides;
    private final ObjectProvider<PlacePhotoService> photos;
    private final AiRequestLimiter limiter;
    private static final String GUEST_TOKEN="recommendationGuestToken";
    private static final String LAST_JOB="lastRecommendationJob";
    /** 실패한 작업 ID → 같은 조건으로 새로 만든 작업 ID. 다시 시도를 두 번 눌러도 새 작업은 하나만 만든다. */
    private static final String RETRIED_JOBS="retriedRecommendationJobs";
    private record Submission(String token, String loginId, UUID jobId) {}

    @PostMapping
    @ResponseBody
    ResponseEntity<Map<String,String>> create(@RequestParam String confirmationToken,
            @RequestParam(defaultValue="false") boolean retry,
            @RequestHeader(value="Accept",defaultValue="") String accept, Principal principal, HttpSession session,
            HttpServletRequest request) {
        RecommendationJobService service=service();
        synchronized(session) {
            var c=(ConfirmedTravelConditions)session.getAttribute(CONFIRMED_KEY);
            if(c==null || !confirmationToken.equals(session.getAttribute(CONFIRMATION_TOKEN)))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"조건이 바뀌었거나 만료됐어요. 다시 확정해 주세요.");
            String loginId=principal==null?null:principal.getName();
            byte[] hash=guestHash(session,true);
            Submission old=(Submission)session.getAttribute(LAST_JOB);
            UUID id;
            if(old!=null && old.token().equals(confirmationToken) && Objects.equals(old.loginId(),loginId)) {
                var view=service.view(old.jobId(),loginId,hash);
                id=retry && "FAILED".equals(view.status())?createLimited(service,c,loginId,hash,request):old.jobId();
            } else id=createLimited(service,c,loginId,hash,request);
            session.setAttribute(LAST_JOB,new Submission(confirmationToken,loginId,id));
            return accepted(id,accept);
        }
    }

    /** 새 추천 작업을 만들기 전에 요청 횟수를 확인한다. 넘었으면 429와 안내 문장을 돌려준다. */
    private UUID createLimited(RecommendationJobService service,ConfirmedTravelConditions c,String loginId,byte[] hash,HttpServletRequest request) {
        checkLimit(loginId,request);
        return service.create(c,loginId,hash);
    }
    private void checkLimit(String loginId,HttpServletRequest request) {
        String limited=limiter.tryAcquire(loginId,request.getRemoteAddr());
        if(limited!=null) throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,limited);
    }
    // 서비스의 트랜잭션 커밋 후 202를 반환한다. 이 시점에는 외부 API나 후보 검색을 기다리지 않는다. 자바스크립트 없는 폼은 303으로 보낸다.
    private static ResponseEntity<Map<String,String>> accepted(UUID id,String accept) {
        String location="/travel/recommendations/"+id;
        if(accept.contains("text/html")) return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION,location).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION,location)
            .cacheControl(CacheControl.noStore()).body(Map.of("jobId",id.toString(),"location",location));
    }

    /**
     * 일시적인 이유(시간 초과·조회 실패 등)로 실패한 작업을 그 작업의 조건 그대로 다시 요청한다.
     * 지금 세션의 다른 조건을 쓰지 않으며, 다시 해도 같은 결과인 실패(지역에 여행지 없음 등)는 서비스가 막는다.
     */
    @PostMapping("/{id}/retry")
    @ResponseBody
    ResponseEntity<Map<String,String>> retry(@PathVariable UUID id,@RequestHeader(value="Accept",defaultValue="") String accept,
            Principal principal,HttpSession session,HttpServletRequest request) {
        RecommendationJobService service=service();
        String loginId=principal==null?null:principal.getName();
        synchronized(session) {
            @SuppressWarnings("unchecked")
            var retried=(Map<UUID,UUID>)session.getAttribute(RETRIED_JOBS);
            if(retried==null) { retried=new HashMap<>(); session.setAttribute(RETRIED_JOBS,retried); }
            UUID made=retried.get(id);
            if(made==null) {
                var view=service.view(id,loginId,guestHash(session,false)); // 소유권을 먼저 확인한 뒤 횟수를 센다.
                if(!view.canRetry()) throw new ResponseStatusException(HttpStatus.CONFLICT,"이 요청은 같은 조건으로 다시 시도할 수 없어요. 조건을 확인하고 새로 요청해 주세요.");
                checkLimit(loginId,request);
                made=service.retry(id,loginId,guestHash(session,false));
                retried.put(id,made);
            }
            return accepted(made,accept);
        }
    }

    /** AI 순위를 더 받을 수 없을 때 보이는 후보로 계속한다. AI를 부르지 않으므로 요청 횟수에 넣지 않는다. */
    @PostMapping("/{id}/continue")
    @ResponseBody
    ResponseEntity<Map<String,String>> continueWithoutAi(@PathVariable UUID id,@RequestHeader(value="Accept",defaultValue="") String accept,
            Principal principal,HttpSession session) {
        var recommendationService=recommendations.getIfAvailable();
        if(recommendationService==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DB 연결 모드에서 여행지 추천을 이용해 주세요.");
        recommendationService.continueWithoutAi(id,principal==null?null:principal.getName(),guestHash(session,false));
        return accepted(id,accept);
    }

    @GetMapping("/{id}")
    String page(@PathVariable UUID id,Principal principal,HttpSession session,Model model) {
        String login=principal==null?null:principal.getName();
        var job=service().view(id,login,guestHash(session,false));
        model.addAttribute("job",job);
        model.addAttribute("themes",service().themes(id,login,guestHash(session,false)));
        model.addAttribute("confirmationToken",session.getAttribute(CONFIRMATION_TOKEN));
        // 대기 제한과 처리 제한을 더한 만큼 상태를 확인한다(recommendation-job.js).
        model.addAttribute("pollSeconds",RecommendationJobService.POLL_SECONDS);
        // 이 추천을 만든 문장 분석에서 해석하지 못해 비운 칸이 있으면 알린다.
        if(session.getAttribute(TravelConditionReviewController.ANALYSIS_NOTICE_KEY) instanceof TravelConditionReviewController.AnalysisNotice notice
                && notice.jobId().equals(id)) model.addAttribute("analysisNotices",notice.messages());
        // 예산을 입력하지 않았으면 전국 평균 지출액과 여행지별 숙소 유형(예산대)을 함께 보여 준다(SFR-03-03).
        var conditions=service().conditions(id,login,guestHash(session,false));
        model.addAttribute("conditions",conditions);
        // 화면에 보이는 여행지: 추천이 끝났으면 순위 목록, 아니면 후보 목록이다.
        List<DestinationCandidate> shown="COMPLETED".equals(job.status())
            ?job.recommendations().stream().map(RankedDestination::destination).toList():job.candidates();
        // 여행지 카드의 관광공사 대표 사진(찾아 둔 것만). 없는 여행지는 글만 보인다.
        model.addAttribute("photoByDestination",photosOf(shown.stream().map(DestinationCandidate::placeId).toList()));
        var guides=budgetGuides.getIfAvailable();
        if(conditions.budget()==null && guides!=null) {
            boolean overnight=(conditions.durationDays()!=null && conditions.durationDays()>1) || Boolean.TRUE.equals(conditions.accommodationNeeded());
            model.addAttribute("spending",BudgetGuide.NATIONAL_AVERAGE);
            model.addAttribute("overnight",overnight);
            // 숙박이 필요한 여행에서만 숙소 예산대를 보여 준다. 당일 여행은 전국 평균만 안내한다.
            // 예산대는 참고 정보라 DB에서 읽지 못하면 빼고 추천 화면은 그대로 보여 준다.
            if(overnight) try { model.addAttribute("budgetTiers",shown.stream().map(d->guides.tiers(d.name(),d.region())).toList()); }
            catch(DataAccessException error) { model.addAttribute("budgetTiersUnavailable",true); }
        }
        return "travel/recommendation-job";
    }
    @PostMapping("/{id}/recommend")
    @ResponseBody
    ResponseEntity<Map<String,String>> recommend(@PathVariable UUID id,@RequestParam int attempt,
            @RequestHeader(value="Accept",defaultValue="") String accept,Principal principal,HttpSession session,
            HttpServletRequest request) {
        var recommendationService=recommendations.getIfAvailable();
        if(recommendationService==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DB 연결 모드에서 여행지 추천을 이용해 주세요.");
        String loginId=principal==null?null:principal.getName();
        // 순위와 이유를 받는 일은 유료 AI 호출이라 요청 횟수를 센다.
        checkLimit(loginId,request);
        recommendationService.enqueue(id,loginId,guestHash(session,false),attempt);
        return accepted(id,accept);
    }
    @GetMapping("/{id}/status")
    @ResponseBody
    ResponseEntity<RecommendationJobView> status(@PathVariable UUID id,Principal principal,HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service().view(id,principal==null?null:principal.getName(),guestHash(session,false)));
    }
    /**
     * 자바스크립트 요청(JSON)의 오류. 화면이 서버가 정한 이유를 그대로 보여 줄 수 있게 {"message": ...}로 돌려준다.
     * 처리하지 못한 DB 오류는 내부 내용을 숨기고 다시 시도하라고 안내한다.
     */
    @ExceptionHandler(value={ResponseStatusException.class,DataAccessException.class},produces=MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    ResponseEntity<Map<String,String>> errorJson(RuntimeException error) {
        return ResponseEntity.status(errorStatus(error)).cacheControl(CacheControl.noStore()).body(Map.of("message",errorMessage(error)));
    }

    /** 화면 요청의 오류. 다른 화면의 오류 화면처럼 이유와 돌아갈 곳을 안내한다. */
    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String errorPage(RuntimeException error,HttpServletResponse response,Model model) {
        int status=errorStatus(error);
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",errorMessage(error));
        model.addAttribute("notFound",status==404);
        return "travel/recommendation-error";
    }
    private static int errorStatus(RuntimeException error) {
        return error instanceof ResponseStatusException e?e.getStatusCode().value():HttpStatus.SERVICE_UNAVAILABLE.value();
    }
    private static String errorMessage(RuntimeException error) {
        if(error instanceof ResponseStatusException e && e.getReason()!=null) return e.getReason();
        if(error instanceof ResponseStatusException e && e.getStatusCode().value()==404) return "요청을 찾을 수 없거나 만료됐어요.";
        return "추천 정보를 처리하지 못했어요. 잠시 후 다시 시도해 주세요.";
    }

    private Map<Long,PlacePhotoDetails.Photo> photosOf(List<Long> placeIds) {
        var service=photos.getIfAvailable();
        if(service==null || placeIds.isEmpty()) return Map.of();
        try { return service.photos(placeIds); } catch(RuntimeException error) { return Map.of(); }
    }
    private RecommendationJobService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DB 연결 모드에서 여행지 조회를 이용해 주세요.");
        return service;
    }
    public static byte[] guestHash(HttpSession session,boolean create) {
        synchronized(session) {
            String token=(String)session.getAttribute(GUEST_TOKEN);
            if(token==null && create) {
                byte[] bytes=new byte[32]; new SecureRandom().nextBytes(bytes);
                token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                session.setAttribute(GUEST_TOKEN,token);
            }
            if(token==null) return null;
            try { return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)); }
            catch(NoSuchAlgorithmException e) { throw new IllegalStateException("SHA-256 unavailable"); }
        }
    }
}
