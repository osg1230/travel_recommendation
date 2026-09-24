package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.ItineraryPlan;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.service.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.security.Principal;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.format.annotation.DateTimeFormat;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.*;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/recommendations")
public class ItineraryController {
    private final ObjectProvider<ItineraryJobService> services;
    private final ObjectProvider<SavedItineraryService> savedItineraries;
    private final ObjectProvider<BusGuideService> busGuides;
    private final ObjectProvider<PlaceCandidateService> candidates;
    private final ObjectProvider<PlaceDiscoveryService> discoveries;
    private final tools.jackson.databind.ObjectMapper mapper;
    private record Submission(UUID parent,long destination,String login,String token,UUID job) {}
    private static final String SESSION_KEY="itinerarySubmission";
    private static final String CONFLICT_KEY="itineraryConstraintConflict";
    private static final String QUICK_KEY="quickItinerary:";
    /** 일정 입력 화면의 값. 필수 조건 충돌로 돌아갈 때 입력을 잃지 않도록 세션에 잠시 둔다. */
    public record ConstraintInput(UUID parent,LocalTime start,LocalTime end,KakaoMapClient.Transport transport,int visitMinutes,
                                  List<Long> required,Integer dailyVisits,boolean dailyRequired,String budgetBasis,
                                  List<String> budgetIncludes,String stayTier,List<TripConstraints.Conflict> conflicts,
                                  Integer durationDays,Boolean accommodationNeeded,LocalDate startDate,List<Long> centers) {}

    @GetMapping("/{id}/destinations/{destinationId}/schedule")
    String form(@PathVariable UUID id,@PathVariable long destinationId,@RequestParam(required=false) String quick,
                Principal principal,HttpSession session,HttpServletResponse response,Model model) {
        response.setHeader("Cache-Control","no-store");
        findMorePlacesIfFew(id,destinationId,principal,session);
        var options=service().options(id,destinationId,login(principal),hash(session));
        model.addAttribute("options",options);
        var pool=service().attractionPool(id,destinationId,login(principal),hash(session));
        model.addAttribute("pool",pool);
        var defaults=defaultPlaces(id,destinationId,principal,session,pool);
        var purposeIds=defaults.purposeIds();
        var centerIds=defaults.centerIds();
        model.addAttribute("purposePlaceIds",purposeIds);
        model.addAttribute("centerPlaceIds",centerIds);
        // 추천 결과의 '일정 만들기'에서 정해지지 않은 값이 있어 넘어온 경우 무엇을 하면 되는지 알린다.
        model.addAttribute("quickMissing",quick!=null);
        model.addAttribute("budgetItems",TripConstraints.BUDGET_ITEMS);
        model.addAttribute("maxDailyVisits",TripConstraints.MAX_DAILY);
        // 필수 조건이 모순돼 돌아온 경우 이유·선택지와 입력했던 값을 다시 보여 준다(한 번만).
        var conflict=session.getAttribute(CONFLICT_KEY) instanceof ConstraintInput previous && previous.parent().equals(id) ? previous : null;
        session.removeAttribute(CONFLICT_KEY);
        // 여행 조건에서 이동수단을 정하지 않았다면 자동차로 미리 고르지 않고 사용자가 직접 고르게 한다.
        var transport=options.conditions().keywordNames("TRANSPORTATION").isEmpty()?null:options.transport();
        model.addAttribute("values",conflict!=null?conflict:new ConstraintInput(id,options.start(),options.end(),transport,
            options.visitMinutes(),purposeIds,null,false,null,List.of(),null,List.of(),null,null,null,centerIds));
        model.addAttribute("stayTiers",BudgetGuide.StayTier.values());
        synchronized(session) {
            var submission=new Submission(id,destinationId,login(principal),UUID.randomUUID().toString(),null);
            session.setAttribute(SESSION_KEY,submission); model.addAttribute("submissionToken",submission.token());
        }
        return "travel/itinerary-form";
    }
    @PostMapping("/{id}/destinations/{destinationId}/schedule")
    ResponseEntity<?> create(@PathVariable UUID id,@PathVariable long destinationId,@RequestParam String submissionToken,
            @RequestParam LocalTime start,@RequestParam LocalTime end,@RequestParam KakaoMapClient.Transport transport,
            @RequestParam int visitMinutes,@RequestParam(required=false) List<Long> required,
            @RequestParam(required=false) Integer dailyVisits,@RequestParam(defaultValue="PREFERRED") String dailyMode,
            @RequestParam(required=false) String budgetBasis,@RequestParam(required=false) List<String> budgetIncludes,
            @RequestParam(required=false) String stayTier,
            @RequestParam(required=false) Integer durationDays,@RequestParam(required=false) Boolean accommodationNeeded,
            @RequestParam(required=false) @DateTimeFormat(iso=DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(name="center",required=false) List<Long> centers,
            Principal principal,HttpSession session,
            @RequestHeader(value="Accept",defaultValue="") String accept) {
        if(!Set.of("REQUIRED","PREFERRED").contains(dailyMode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"하루 방문 수의 구분을 다시 골라 주세요.");
        String basis=budgetBasis==null || budgetBasis.isBlank()?null:budgetBasis;
        String tier=stayTier==null || stayTier.isBlank()?null:stayTier;
        var constraints=new ItineraryPlan.Constraints(required,dailyVisits,dailyVisits!=null && "REQUIRED".equals(dailyMode),basis,budgetIncludes,tier,centers);
        UUID job;
        synchronized(session) {
            var submission=(Submission)session.getAttribute(SESSION_KEY);
            if(submission==null || !submission.parent().equals(id) || submission.destination()!=destinationId
                || !Objects.equals(submission.login(),login(principal)) || !submission.token().equals(submissionToken))
                throw new ResponseStatusException(HttpStatus.CONFLICT,"입력 화면이 만료됐어요. 장소 목록에서 일정 만들기를 다시 열어 주세요.");
            if(submission.job()!=null) {
                job=submission.job(); service().view(job,login(principal),hash(session));
            } else {
                try {
                    job=service().create(id,destinationId,login(principal),hash(session),start,end,transport,visitMinutes,constraints,durationDays,accommodationNeeded,startDate);
                } catch(TripConstraints.ConflictException conflict) {
                    // 모순된 필수 조건은 임의로 완화하지 않고 입력 화면으로 돌아가 이유와 선택지를 보여 준다.
                    session.setAttribute(CONFLICT_KEY,new ConstraintInput(id,start,end,transport,visitMinutes,constraints.requiredPlaceIds(),
                        dailyVisits,constraints.dailyVisitsRequired(),basis,constraints.budgetIncludes(),tier,conflict.conflicts(),durationDays,accommodationNeeded,startDate,constraints.centerPlaceIds()));
                    session.removeAttribute(SESSION_KEY);
                    return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/travel/recommendations/"+id+"/destinations/"+destinationId+"/schedule"))
                        .cacheControl(CacheControl.noStore()).build();
                }
                session.setAttribute(SESSION_KEY,new Submission(id,destinationId,login(principal),submissionToken,job));
            }
        }
        String location="/travel/recommendations/itineraries/"+job;
        return accept.contains("application/json")?ResponseEntity.accepted().cacheControl(CacheControl.noStore()).body(Map.of("jobId",job,"location",location)):
            ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).cacheControl(CacheControl.noStore()).build();
    }
    /**
     * 추천 결과에서 장소 목록과 하루 시간 입력을 거치지 않고 바로 일정 초안을 만든다.
     * 하루 시간·체류시간·중심 장소·목적 시설은 입력 화면과 같은 기본값을 쓰고, 마음에 들지 않으면 초안에서 수정을 요청한다.
     * 여행 일수·숙박·출발일·이동수단 중 정해지지 않은 값이 있으면 추측하지 않고 하루 시간 정하기 화면으로 보낸다.
     */
    @PostMapping("/{id}/destinations/{destinationId}/quick-schedule")
    ResponseEntity<?> quick(@PathVariable UUID id,@PathVariable long destinationId,Principal principal,HttpSession session) {
        String formUrl="/travel/recommendations/"+id+"/destinations/"+destinationId+"/schedule";
        UUID job;
        synchronized(session) {
            // 두 번 누르거나 뒤로 가서 다시 눌러도 같은 초안으로 보낸다. 다른 시간으로 만들려면 초안의 '시간을 조정해서 다시 만들기'를 쓴다.
            String key=QUICK_KEY+id+":"+destinationId+":"+login(principal);
            if(session.getAttribute(key) instanceof UUID made) return seeOther("/travel/recommendations/itineraries/"+made);
            findMorePlacesIfFew(id,destinationId,principal,session);
            var options=service().options(id,destinationId,login(principal),hash(session));
            var c=options.conditions();
            boolean ready=c.durationDays()!=null && (c.durationDays()==1 || c.accommodationNeeded()!=null)
                && c.startDate()!=null && !c.keywordNames("TRANSPORTATION").isEmpty();
            if(!ready) return seeOther(formUrl+"?quick=missing");
            var pool=service().attractionPool(id,destinationId,login(principal),hash(session));
            var defaults=defaultPlaces(id,destinationId,principal,session,pool);
            var constraints=new ItineraryPlan.Constraints(defaults.purposeIds(),null,false,null,List.of(),null,defaults.centerIds());
            try {
                job=service().create(id,destinationId,login(principal),hash(session),options.start(),options.end(),options.transport(),
                    options.visitMinutes(),constraints,null,null,null);
            } catch(TripConstraints.ConflictException conflict) {
                // 기본값으로도 조건이 맞지 않으면 입력 화면에서 이유와 선택지를 보여 준다.
                session.setAttribute(CONFLICT_KEY,new ConstraintInput(id,options.start(),options.end(),options.transport(),options.visitMinutes(),
                    constraints.requiredPlaceIds(),null,false,null,List.of(),null,conflict.conflicts(),null,null,null,constraints.centerPlaceIds()));
                return seeOther(formUrl);
            }
            session.setAttribute(key,job);
        }
        return seeOther("/travel/recommendations/itineraries/"+job);
    }
    @GetMapping("/itineraries/{id}")
    String result(@PathVariable UUID id,Principal principal,HttpSession session,HttpServletResponse response,Model model) {
        response.setHeader("Cache-Control","no-store");
        var job=service().view(id,login(principal),hash(session));
        model.addAttribute("job",job);
        // 확정 조건과 일정을 서버가 비교한 결과를 함께 보여 준다.
        if(job.result()!=null) {
            model.addAttribute("report",ItineraryConditionReport.evaluate(job.input(),job.result()));
            // 일차별 방문 순서와 구간별 길찾기 링크(SFR-11-02·03).
            var route=TripMap.route(job.result().days().stream().map(d->new TripMap.Day(d.number(),
                d.items().stream().map(i->new TripMap.Stop(i.type(),i.name(),i.mapUrl())).toList())).toList(),
                job.result().transport()==KakaoMapClient.Transport.CAR);
            model.addAttribute("route",route); model.addAttribute("mapJson",mapper.writeValueAsString(route));
            // 지역 대표 음식 안내(SFR-17-01). 공식 출처로 확인한 지역만 보여 준다.
            model.addAttribute("foods",RegionalFoods.of(job.result().destination()).orElse(null));
            // 수정안에서 바꿀 수 있는 관광지는 실제로 배치된 곳뿐이다(시간이 부족해 뺀 곳 제외).
            var placed=ItineraryJobService.placed(job.input(),job.result());
            model.addAttribute("placedAttractions",placed);
            // 일정 항목마다 '다른 곳으로 바꾸기'를 보여 줄지 정하는 데 쓴다.
            model.addAttribute("placedAttractionIds",placed.stream().map(p->p.placeId()).collect(java.util.stream.Collectors.toSet()));
            // 버튼으로 찾은 숙소·음식점 참고 가격. 일정 항목 옆에 보여 주도록 장소 ID로 묶는다.
            var priceByPlace=new java.util.HashMap<Long,ItineraryPlan.PriceQuote>();
            if(job.result().prices()!=null) job.result().prices().quotes().forEach(q->priceByPlace.put(q.placeId(),q));
            model.addAttribute("priceByPlace",priceByPlace);
            model.addAttribute("costEstimate",TripCostEstimate.of(job.input(),job.result()));
            // 조회 시각은 한국 시간으로 보여 준다.
            model.addAttribute("pricesCheckedAt",job.result().prices()==null?null:java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                .format(job.result().prices().checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul"))));
            // 장소에 저장해 둔 이전 검색 결과를 다시 쓴 곳은 그 검색 시각을 따로 보여 준다.
            var reusedAt=new java.util.HashMap<Long,String>();
            if(job.result().prices()!=null) job.result().prices().quotes().stream()
                .filter(q->q.reusedBefore(job.result().prices().checkedAt()))
                .forEach(q->reusedAt.put(q.placeId(),java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
                    .format(q.checkedAt().atZone(java.time.ZoneId.of("Asia/Seoul")))));
            model.addAttribute("priceReusedAt",reusedAt);
        }
        // 내 일정 저장은 회원만 할 수 있어 화면에서 로그인 여부를 구분한다.
        model.addAttribute("loggedIn",principal!=null);
        // 수정안이면 이어진 저장 일정에 덮어쓸 수 있게 대상 일정을 함께 보여 준다.
        var saved=savedItineraries.getIfAvailable();
        model.addAttribute("applyTargets",saved==null || job.result()==null || job.input().revision()==null?List.of():saved.targets(id,login(principal)));
        return "travel/itinerary-result";
    }
    /** 유지·교체할 관광지와 시간을 받아 새 수정안 작업을 만든다. 원본 초안은 그대로 둔다. */
    @PostMapping("/itineraries/{id}/revisions")
    ResponseEntity<?> revise(@PathVariable UUID id,@RequestParam(required=false) List<Long> replace,
            @RequestParam(required=false) ItineraryJobService.ReplaceReason reason,@RequestParam LocalTime start,
            @RequestParam LocalTime end,@RequestParam int visitMinutes,Principal principal,HttpSession session) {
        UUID job=service().revise(id,login(principal),hash(session),replace,reason,start,end,visitMinutes);
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create("/travel/recommendations/itineraries/"+job))
            .cacheControl(CacheControl.noStore()).build();
    }
    private static final String AI_COOLDOWN_KEY="itineraryAiRevisionNext";
    /** 초안마다 "후보에 없다"고 되물은 장소 이름. 같은 장소를 다시 요청하면 되묻지 않고 비슷한 곳으로 바꾼다. */
    private static final String AI_ASKED_KEY="itineraryAiAskedPlaces";
    private static final String PRICE_COOLDOWN_KEY="itineraryPriceLookupNext";

    /** 초안의 숙소·음식점 참고 가격을 AI 웹 검색으로 찾는다. 유료 호출이라 버튼을 누를 때만 하고, 세션마다 30초 간격을 둔다. */
    @PostMapping("/itineraries/{id}/prices")
    String prices(@PathVariable UUID id,Principal principal,HttpSession session,
                  org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        String back="redirect:/travel/recommendations/itineraries/"+id+"#prices-title";
        synchronized(session) {
            var next=session.getAttribute(PRICE_COOLDOWN_KEY) instanceof java.time.Instant at?at:null;
            if(next!=null && next.isAfter(java.time.Instant.now())) {
                redirect.addFlashAttribute("priceError","방금 가격을 찾았어요. 잠시 후 다시 시도해 주세요.");
                return back;
            }
            session.setAttribute(PRICE_COOLDOWN_KEY,java.time.Instant.now().plusSeconds(30));
        }
        try {
            service().lookupPrices(id,login(principal),hash(session));
        } catch(RecommendationException e) {
            redirect.addFlashAttribute("priceError",e.getMessage()+" 일정 초안은 그대로예요.");
        } catch(ResponseStatusException e) {
            if(e.getStatusCode().value()==404) throw e;
            redirect.addFlashAttribute("priceError",e.getReason());
        }
        return back;
    }

    /**
     * 문장으로 받은 수정 요청을 AI로 수정안 형식으로 바꿔 새 수정안 작업을 만든다(SFR-08-04).
     * 되묻는 질문이나 실패 사유는 원래 초안 화면에 그대로 보여 주고, 원래 초안은 바꾸지 않는다.
     */
    @PostMapping("/itineraries/{id}/ai-revisions")
    String aiRevise(@PathVariable UUID id,@RequestParam(defaultValue="") String request,Principal principal,HttpSession session,
                    org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        String back="redirect:/travel/recommendations/itineraries/"+id+"#ai-revise-title";
        redirect.addFlashAttribute("aiRequest",request.length()>300?request.substring(0,300):request);
        // 유료 호출이므로 같은 세션에서 연달아 누르지 않도록 20초 간격을 둔다.
        synchronized(session) {
            var next=session.getAttribute(AI_COOLDOWN_KEY) instanceof java.time.Instant at?at:null;
            if(next!=null && next.isAfter(java.time.Instant.now())) {
                redirect.addFlashAttribute("aiError","방금 요청을 처리했어요. 잠시 후 다시 시도해 주세요.");
                return back;
            }
            session.setAttribute(AI_COOLDOWN_KEY,java.time.Instant.now().plusSeconds(20));
        }
        try {
            java.util.Set<String> asked;
            synchronized(session) {
                @SuppressWarnings("unchecked")
                var map=session.getAttribute(AI_ASKED_KEY) instanceof java.util.Map<?,?> m?(java.util.Map<UUID,java.util.Set<String>>)m:null;
                asked=map==null?java.util.Set.of():java.util.Set.copyOf(map.getOrDefault(id,java.util.Set.of()));
            }
            var result=service().aiRevise(id,login(principal),hash(session),request,asked);
            if(!result.askedPlaces().isEmpty()) synchronized(session) {
                @SuppressWarnings("unchecked")
                var map=session.getAttribute(AI_ASKED_KEY) instanceof java.util.Map<?,?> m?(java.util.Map<UUID,java.util.Set<String>>)m:new java.util.HashMap<UUID,java.util.Set<String>>();
                var names=new java.util.HashSet<>(map.getOrDefault(id,java.util.Set.of())); names.addAll(result.askedPlaces());
                map.put(id,names); session.setAttribute(AI_ASKED_KEY,map);
            }
            if(result.job()!=null) return "redirect:/travel/recommendations/itineraries/"+result.job();
            if(result.notice()!=null) redirect.addFlashAttribute("aiNotice",result.notice());
            else redirect.addFlashAttribute("aiQuestion",result.question());
        } catch(RecommendationException e) {
            redirect.addFlashAttribute("aiError",e.getMessage()+" 원래 초안은 그대로예요.");
        } catch(ResponseStatusException e) {
            if(e.getStatusCode().value()==404) throw e;
            redirect.addFlashAttribute("aiError",e.getReason());
        }
        return back;
    }
    private static final String BUS_COOLDOWN_KEY="itineraryBusGuideNext";

    /**
     * 요청 시 지역 간 버스 안내(SFR-06-05·06). 출발 위치에서 나온 결과라 저장하지 않고 다음 화면에 한 번만 보여 준다.
     * 좌표는 도시 이름을 찾는 데만 쓰고 세션·로그·DB에 남기지 않는다.
     */
    @PostMapping("/itineraries/{id}/bus-guide")
    String busGuide(@PathVariable UUID id,@RequestParam(defaultValue="") String to,@RequestParam(defaultValue="") String from,
                    @RequestParam(required=false) Double longitude,@RequestParam(required=false) Double latitude,
                    Principal principal,HttpSession session,
                    org.springframework.web.servlet.mvc.support.RedirectAttributes redirect) {
        String back="redirect:/travel/recommendations/itineraries/"+id+"#bus-title";
        redirect.addFlashAttribute("busTo",to.length()>40?"":to);
        redirect.addFlashAttribute("busFrom",from.length()>20?from.substring(0,20):from);
        synchronized(session) {
            var next=session.getAttribute(BUS_COOLDOWN_KEY) instanceof java.time.Instant at?at:null;
            if(next!=null && next.isAfter(java.time.Instant.now())) {
                redirect.addFlashAttribute("busError","방금 조회했어요. 10초 후에 다시 찾아 주세요.");
                return back;
            }
            session.setAttribute(BUS_COOLDOWN_KEY,java.time.Instant.now().plusSeconds(10));
        }
        var guides=busGuides.getIfAvailable();
        if(guides==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"버스 안내는 DB 연결 후 이용할 수 있어요.");
        try {
            redirect.addFlashAttribute("busGuide",guides.guide(id,login(principal),hash(session),to,from,longitude,latitude));
        } catch(RecommendationException e) {
            redirect.addFlashAttribute("busError",e.getMessage());
        }
        return back;
    }

    @GetMapping("/itineraries/{id}/status")
    @ResponseBody
    ResponseEntity<?> status(@PathVariable UUID id,Principal principal,HttpSession session) {
        var job=service().view(id,login(principal),hash(session));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(Map.of("status",job.status(),"message",job.message(),"pending",job.pending()));
    }
    @ExceptionHandler({ResponseStatusException.class,RecommendationException.class,DataAccessException.class})
    String error(RuntimeException error,HttpServletRequest request,HttpServletResponse response,HttpSession session,Model model) {
        int status=400; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else if(error instanceof RecommendationException e) message=e.getMessage();
        else { status=503; message="일정 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store"); model.addAttribute("message",message);
        // 찾을 수 없음(만료·다른 세션)이면 비회원에게 세션 만료 가능성을 함께 안내한다.
        model.addAttribute("notFound",status==404);
        var submission=(Submission)session.getAttribute(SESSION_KEY);
        // 수정안을 만들지 못하면 원본 초안은 그대로이므로 원본으로 돌아가는 길을 안내한다.
        var revision=java.util.regex.Pattern.compile("^/travel/recommendations/itineraries/([0-9a-fA-F-]{36})/revisions$").matcher(request.getRequestURI().substring(request.getContextPath().length()));
        if(revision.matches()) model.addAttribute("sourceUrl","/travel/recommendations/itineraries/"+revision.group(1));
        if(submission!=null) model.addAttribute("editUrl","/travel/recommendations/"+submission.parent()+"/destinations/"+submission.destination()+"/schedule");
        return "travel/itinerary-error";
    }
    private ItineraryJobService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"일정 생성은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
    private static ResponseEntity<?> seeOther(String location) {
        return ResponseEntity.status(HttpStatus.SEE_OTHER).location(URI.create(location)).cacheControl(CacheControl.noStore()).build();
    }
    private record DefaultPlaces(List<Long> purposeIds,List<Long> centerIds) {}
    // 여행 목적 시설 중 첫 곳만 필수 방문 장소로 미리 골라 둔다. 여러 곳을 모두 필수로 두면 먼 시설까지 억지로 넣게 된다.
    // 중심 장소와 같은 종류(예: 서원)인 목적 시설은 중심 장소가 목적을 채우므로 고르지 않는다. 입력 화면에서는 사용자가 바꿀 수 있다.
    private DefaultPlaces defaultPlaces(UUID id,long destinationId,Principal principal,HttpSession session,List<PlaceCandidate> pool) {
        var centerIds=service().defaultCenterPlaceIds(id,destinationId,login(principal),hash(session));
        var poolIds=pool.stream().map(p->p.placeId()).toList();
        var purposeIds=CenterPlaces.notCoveredByCenters(pool,
            service().purposePlaceIds(id,destinationId,login(principal),hash(session)).stream().filter(poolIds::contains).toList(),centerIds)
            .stream().limit(1).toList();
        return new DefaultPlaces(purposeIds,centerIds);
    }
    // 장소 목록을 거치지 않고 일정을 만들 때도 후보가 부족하면 장소 목록 화면처럼 카카오에서 한 번 더 찾는다(같은 세션 키라 한 번만).
    private void findMorePlacesIfFew(UUID id,long destinationId,Principal principal,HttpSession session) {
        var list=candidates.getIfAvailable(); var discovery=discoveries.getIfAvailable();
        if(list==null || discovery==null) return;
        String autoKey="autoPlaceLookup:"+id+":"+destinationId;
        if(session.getAttribute(autoKey)!=null) return;
        try {
            if(!list.find(id,destinationId,login(principal),hash(session)).tooFewForSchedule()) return;
            session.setAttribute(autoKey,Boolean.TRUE);
            discovery.refresh(id,destinationId,login(principal),hash(session));
        } catch(RuntimeException lookupFailed) {
            // 더 찾지 못해도 지금 있는 후보로 일정을 만든다.
        }
    }
    private byte[] hash(HttpSession session) { return RecommendationJobController.guestHash(session,false); }
}
