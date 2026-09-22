package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.ItineraryPlan;
import com.travel.travel_recommendation.service.*;
import jakarta.servlet.http.*;
import java.net.URI;
import java.security.Principal;
import java.time.LocalTime;
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
    private final tools.jackson.databind.ObjectMapper mapper;
    private record Submission(UUID parent,long destination,String login,String token,UUID job) {}
    private static final String SESSION_KEY="itinerarySubmission";
    private static final String CONFLICT_KEY="itineraryConstraintConflict";
    /** 일정 입력 화면의 값. 필수 조건 충돌로 돌아갈 때 입력을 잃지 않도록 세션에 잠시 둔다. */
    public record ConstraintInput(UUID parent,LocalTime start,LocalTime end,KakaoMapClient.Transport transport,int visitMinutes,
                                  List<Long> required,Integer dailyVisits,boolean dailyRequired,String budgetBasis,
                                  List<String> budgetIncludes,String stayTier,List<TripConstraints.Conflict> conflicts) {}

    @GetMapping("/{id}/destinations/{destinationId}/schedule")
    String form(@PathVariable UUID id,@PathVariable long destinationId,Principal principal,HttpSession session,
                HttpServletResponse response,Model model) {
        response.setHeader("Cache-Control","no-store");
        var options=service().options(id,destinationId,login(principal),hash(session));
        model.addAttribute("options",options);
        model.addAttribute("pool",service().attractionPool(id,destinationId,login(principal),hash(session)));
        model.addAttribute("budgetItems",TripConstraints.BUDGET_ITEMS);
        model.addAttribute("maxDailyVisits",TripConstraints.MAX_DAILY);
        // 필수 조건이 모순돼 돌아온 경우 이유·선택지와 입력했던 값을 다시 보여 준다(한 번만).
        var conflict=session.getAttribute(CONFLICT_KEY) instanceof ConstraintInput previous && previous.parent().equals(id) ? previous : null;
        session.removeAttribute(CONFLICT_KEY);
        model.addAttribute("values",conflict!=null?conflict:new ConstraintInput(id,options.start(),options.end(),options.transport(),
            options.visitMinutes(),List.of(),null,false,null,List.of(),null,List.of()));
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
            Principal principal,HttpSession session,
            @RequestHeader(value="Accept",defaultValue="") String accept) {
        if(!Set.of("REQUIRED","PREFERRED").contains(dailyMode)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"하루 방문 수의 구분을 다시 골라 주세요.");
        String basis=budgetBasis==null || budgetBasis.isBlank()?null:budgetBasis;
        String tier=stayTier==null || stayTier.isBlank()?null:stayTier;
        var constraints=new ItineraryPlan.Constraints(required,dailyVisits,dailyVisits!=null && "REQUIRED".equals(dailyMode),basis,budgetIncludes,tier);
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
                    job=service().create(id,destinationId,login(principal),hash(session),start,end,transport,visitMinutes,constraints);
                } catch(TripConstraints.ConflictException conflict) {
                    // 모순된 필수 조건은 임의로 완화하지 않고 입력 화면으로 돌아가 이유와 선택지를 보여 준다.
                    session.setAttribute(CONFLICT_KEY,new ConstraintInput(id,start,end,transport,visitMinutes,constraints.requiredPlaceIds(),
                        dailyVisits,constraints.dailyVisitsRequired(),basis,constraints.budgetIncludes(),tier,conflict.conflicts()));
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
            model.addAttribute("placedAttractions",ItineraryJobService.placed(job.input(),job.result()));
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
            var result=service().aiRevise(id,login(principal),hash(session),request);
            if(result.job()!=null) return "redirect:/travel/recommendations/itineraries/"+result.job();
            redirect.addFlashAttribute("aiQuestion",result.question());
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
    private byte[] hash(HttpSession session) { return RecommendationJobController.guestHash(session,false); }
}
