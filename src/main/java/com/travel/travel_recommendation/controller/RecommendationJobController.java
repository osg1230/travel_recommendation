package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.RecommendationJobService;
import com.travel.travel_recommendation.service.DestinationRecommendationService;
import jakarta.servlet.http.HttpSession;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
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
    private static final String GUEST_TOKEN="recommendationGuestToken";
    private static final String LAST_JOB="lastRecommendationJob";
    private record Submission(String token, String loginId, UUID jobId) {}

    @PostMapping
    @ResponseBody
    ResponseEntity<Map<String,String>> create(@RequestParam String confirmationToken,
            @RequestParam(defaultValue="false") boolean retry,
            @RequestHeader(value="Accept",defaultValue="") String accept, Principal principal, HttpSession session) {
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
                id=retry && "FAILED".equals(view.status())?service.create(c,loginId,hash):old.jobId();
            } else id=service.create(c,loginId,hash);
            session.setAttribute(LAST_JOB,new Submission(confirmationToken,loginId,id));
            String location="/travel/recommendations/"+id;
            // 서비스의 트랜잭션 커밋 후 202를 반환한다. 이 시점에는 외부 API나 후보 검색을 기다리지 않는다.
            if(accept.contains("text/html")) return ResponseEntity.status(HttpStatus.SEE_OTHER)
                .header(HttpHeaders.LOCATION,location).cacheControl(CacheControl.noStore()).build();
            return ResponseEntity.accepted().header(HttpHeaders.LOCATION,location)
                .cacheControl(CacheControl.noStore()).body(Map.of("jobId",id.toString(),"location",location));
        }
    }

    @GetMapping("/{id}")
    String page(@PathVariable UUID id,Principal principal,HttpSession session,Model model) {
        model.addAttribute("job",service().view(id,principal==null?null:principal.getName(),guestHash(session,false)));
        model.addAttribute("confirmationToken",session.getAttribute(CONFIRMATION_TOKEN));
        return "travel/recommendation-job";
    }
    @PostMapping("/{id}/recommend")
    @ResponseBody
    ResponseEntity<Map<String,String>> recommend(@PathVariable UUID id,@RequestParam int attempt,
            @RequestHeader(value="Accept",defaultValue="") String accept,Principal principal,HttpSession session) {
        var recommendationService=recommendations.getIfAvailable();
        if(recommendationService==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE);
        recommendationService.enqueue(id,principal==null?null:principal.getName(),guestHash(session,false),attempt);
        String location="/travel/recommendations/"+id;
        if(accept.contains("text/html")) return ResponseEntity.status(HttpStatus.SEE_OTHER)
            .header(HttpHeaders.LOCATION,location).cacheControl(CacheControl.noStore()).build();
        return ResponseEntity.accepted().header(HttpHeaders.LOCATION,location).cacheControl(CacheControl.noStore())
            .body(Map.of("jobId",id.toString(),"location",location));
    }
    @GetMapping("/{id}/status")
    @ResponseBody
    ResponseEntity<RecommendationJobView> status(@PathVariable UUID id,Principal principal,HttpSession session) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
            .body(service().view(id,principal==null?null:principal.getName(),guestHash(session,false)));
    }
    private RecommendationJobService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"DB 연결 모드에서 여행지 조회를 이용해 주세요.");
        return service;
    }
    static byte[] guestHash(HttpSession session,boolean create) {
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
