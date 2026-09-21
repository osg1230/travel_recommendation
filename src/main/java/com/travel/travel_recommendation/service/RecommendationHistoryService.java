package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.RecommendationHistory.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.text.DecimalFormat;
import java.time.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 회원의 추천 이력 목록·상세·삭제(SFR-14-02~04). 본인 이력만 다루며 저장한 일정은 이력을 지워도 남긴다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class RecommendationHistoryService {
    private final RecommendationRequestRepository requests;
    private final RecommendationCandidateRepository candidates;
    private final AiJobRepository jobs;
    private final ItineraryRepository itineraries;
    private final UserRepository users;
    private final ObjectMapper mapper;
    private static final Set<String> PENDING=Set.of("QUEUED","ANALYZING_CONDITIONS","SEARCHING_CANDIDATES",
        "RECOMMENDATION_QUEUED","RANKING_CANDIDATES","GENERATING_SCHEDULE","VALIDATING_SCHEDULE");

    @Transactional(readOnly=true,timeout=10)
    public List<Summary> list(String loginId) {
        Long userId=member(loginId);
        var found=requests.findTop100ByUserIdOrderByCreatedAtDescIdDesc(userId);
        if(found.isEmpty()) return List.of();
        var byRequest=candidates.findByRequestIdInOrderByRequestIdAscRankAsc(found.stream().map(RecommendationRequest::getId).toList())
            .stream().collect(Collectors.groupingBy(RecommendationCandidate::getRequestId));
        return found.stream().map(r->summary(r,byRequest.getOrDefault(r.getId(),List.of()),userId)).toList();
    }

    @Transactional(readOnly=true,timeout=10)
    public Detail detail(Long id, String loginId) {
        Long userId=member(loginId);
        var request=owned(id,userId);
        var ranked=candidates.findByRequestIdOrderByRank(id);
        var destinations=ranked.stream().map(c->{
            var snapshot=mapper.readValue(c.getSnapshot(),DestinationCandidate.class);
            return new Destination(c.getRank(),snapshot.name(),snapshot.region(),c.getReason(),snapshot.sourceUrl());
        }).toList();
        // 추천 결과 화면은 작업 보관 기간 안에서만 다시 열 수 있다.
        UUID result=jobs.findByRequestIdAndUserId(id,userId).stream()
            .filter(j->"RECOMMENDATION".equals(j.getJobType()) && "COMPLETED".equals(j.getStatus()) && j.getExpiresAt().isAfter(now()))
            .map(AiJob::getId).findFirst().orElse(null);
        return new Detail(summary(request,ranked,userId),request.getOriginalText(),request.getInterpretationSummary(),
            conditions(request),destinations,result);
    }

    /** 이력과 추천 결과·임시 작업을 지운다. 저장한 일정은 남기고 이력 연결만 끊는다. 처리 중인 작업이 있으면 지우지 않는다. */
    @Transactional(timeout=10)
    public void delete(Long id, String loginId) {
        Long userId=member(loginId);
        var request=owned(id,userId);
        if(jobs.findByRequestIdAndUserId(id,userId).stream().anyMatch(j->PENDING.contains(j.getStatus())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"아직 처리 중인 추천이나 일정이 있어요. 처리가 끝난 뒤 삭제해 주세요.");
        itineraries.detachRequest(id,userId);
        candidates.deleteByRequest(id);
        jobs.deleteByRequest(id,userId);
        requests.delete(request);
        requests.flush();
    }

    private Summary summary(RecommendationRequest r, List<RecommendationCandidate> ranked, Long userId) {
        String status;
        if(!ranked.isEmpty()) status="DONE";
        else status=jobs.findByRequestIdAndUserId(r.getId(),userId).stream().anyMatch(j->PENDING.contains(j.getStatus()))?"PENDING":"NONE";
        var names=ranked.stream().limit(3).map(c->mapper.readValue(c.getSnapshot(),DestinationCandidate.class).name()).toList();
        return new Summary(r.getId(),r.getCreatedAt(),"TEXT".equals(r.getInputMode())?"문장 입력":"조건 선택",
            r.getRegion()==null?"지역 상관없음":r.getRegion(),period(r),names,status);
    }

    private List<Condition> conditions(RecommendationRequest r) {
        JsonNode json=mapper.readTree(r.getConditionsJson());
        var list=new ArrayList<Condition>();
        list.add(new Condition("지역",r.getRegion()==null?"상관없음":r.getRegion()));
        list.add(new Condition("기간",period(r)));
        list.add(new Condition("예산",r.getBudget()==null?"상관없음":new DecimalFormat("#,###").format(r.getBudget())+"원"));
        list.add(new Condition("인원",json.path("party_size").isNumber()?json.path("party_size").asInt()+"명":"상관없음"));
        list.add(new Condition("숙박",r.getAccommodationNeeded()==null?"미정":r.getAccommodationNeeded()?"필요":"필요 없음"));
        list.add(new Condition("계절",text(json.path("season"))));
        list.add(new Condition("동행",text(json.path("companion"))));
        var themes=new ArrayList<String>(); json.path("theme").forEach(t->themes.add(t.asText()));
        list.add(new Condition("여행 취향",themes.isEmpty()?"상관없음":String.join(", ",themes)));
        list.add(new Condition("이동수단",text(json.path("transportation"))));
        list.add(new Condition("주변 지역 포함",json.path("include_nearby").isBoolean()?(json.path("include_nearby").asBoolean()?"포함":"포함하지 않음"):"상관없음"));
        return List.copyOf(list);
    }
    private static String text(JsonNode node) { return node.isTextual() && !node.asText().isBlank()?node.asText():"상관없음"; }
    private static String period(RecommendationRequest r) {
        if(r.getStartDate()!=null) return r.getStartDate()+" ~ "+r.getEndDate()+" ("+r.getDurationDays()+"일)";
        return r.getDurationDays()==null?"기간 미정":r.getDurationDays()+"일 (날짜 미정)";
    }
    private RecommendationRequest owned(Long id, Long userId) {
        return requests.findByIdAndUserId(id,userId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"추천 이력을 찾을 수 없어요."));
    }
    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
    private static LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
