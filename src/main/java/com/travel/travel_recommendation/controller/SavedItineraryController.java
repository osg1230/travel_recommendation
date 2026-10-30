package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.SavedItineraryService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 회원이 저장한 내 일정의 저장·목록·상세·이름 수정·삭제 화면을 처리한다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/itineraries")
public class SavedItineraryController {
    private final ObjectProvider<SavedItineraryService> services;
    private final ObjectProvider<com.travel.travel_recommendation.service.SavedItineraryEditService> edits;
    private final ObjectProvider<com.travel.travel_recommendation.service.PlaceRatingService> ratings;
    private final ObjectProvider<com.travel.travel_recommendation.service.FavoriteService> favorites;
    private final ObjectProvider<com.travel.travel_recommendation.service.VirtualReservationService> reservations;
    private final ObjectProvider<com.travel.travel_recommendation.service.PlacePhotoService> photos;
    private final ObjectProvider<com.travel.travel_recommendation.service.TransitRouteService> transitRoutes;
    private final tools.jackson.databind.ObjectMapper mapper;

    @PostMapping
    String save(@RequestParam UUID jobId, Principal principal, HttpSession session, RedirectAttributes redirect) {
        // 로그인 전에 이 세션에서 만든 비회원 초안도 저장할 수 있도록 세션의 비회원 토큰을 함께 넘긴다.
        var saved=service().save(jobId,login(principal),RecommendationJobController.guestHash(session,false));
        redirect.addFlashAttribute("savedMessage",saved.created()
            ?"내 일정으로 저장했어요. 이름은 아래에서 바꿀 수 있어요."
            :"이미 저장한 일정이에요. 저장해 둔 내용을 보여 드릴게요.");
        return "redirect:/my/itineraries/"+saved.id();
    }

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("itineraries",service().list(login(principal)));
        // 목록 카드의 이동수단과 대표 사진(그 일정에서 사진이 있는 첫 관광지). 사진을 찾지 못해도 목록은 그대로 보여 준다.
        var extras=service().listExtras(login(principal));
        var thumbs=new java.util.HashMap<Long,Object>();
        var photo=photos.getIfAvailable();
        try {
            if(photo!=null && !extras.isEmpty()) {
                var found=photo.photos(extras.values().stream().flatMap(extra->extra.visitPlaceIds().stream()).distinct().toList());
                extras.forEach((id,extra)->extra.visitPlaceIds().stream().filter(found::containsKey).findFirst()
                    .ifPresent(placeId->thumbs.put(id,found.get(placeId))));
            }
        } catch(RuntimeException error) { thumbs.clear(); }
        model.addAttribute("extras",extras); model.addAttribute("thumbs",thumbs);
        return "my/itineraries";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        var itinerary=service().detail(id,login(principal));
        model.addAttribute("itinerary",itinerary);
        // 저장 당시 좌표로 방문 순서와 구간별 길찾기 링크를 보여 준다(SFR-11-02·03).
        var route=com.travel.travel_recommendation.service.TripMap.route(itinerary.days().stream()
            .map(d->new com.travel.travel_recommendation.service.TripMap.Day(d.number(),d.items().stream()
                .map(i->new com.travel.travel_recommendation.service.TripMap.Stop(i.type(),i.name(),i.mapUrl())).toList())).toList(),
            "자동차".equals(itinerary.transportName()));
        model.addAttribute("route",route); model.addAttribute("mapJson",mapper.writeValueAsString(route));
        // 대중교통 일정은 지도에서 그날의 실제 대중교통·도보 경로를 눌러서 조회할 수 있다(저장하지 않고 볼 때마다 조회).
        if(!"자동차".equals(itinerary.transportName())) model.addAttribute("dayPathsUrl","/my/itineraries/"+id+"/day-paths");
        // 다녀온 장소마다 평가 요약과 평가하기 링크를 보여 준다(SFR-10).
        var rating=ratings.getIfAvailable();
        var ratedPlaceIds=itinerary.days().stream()
            .flatMap(d->d.items().stream()).filter(i->i.placeId()!=null && java.util.Set.of("VISIT","MEAL","STAY").contains(i.type()))
            .map(com.travel.travel_recommendation.dto.SavedItinerary.Item::placeId).toList();
        model.addAttribute("ratingSummaries",rating==null?java.util.Map.of():rating.summaries(ratedPlaceIds));
        // 이미 평가한 장소는 '평가하기' 대신 '내 평가 ★4 · 바꾸기'로 보여 준다.
        model.addAttribute("myRatingScores",rating==null?java.util.Map.of():rating.myScores(ratedPlaceIds,login(principal)));
        // 관광지·숙소는 이 화면에서도 즐겨찾기에 넣거나 뺄 수 있게 등록 여부를 넘긴다(SFR-09, 음식점은 대상이 아니다).
        var favorite=favorites.getIfAvailable();
        model.addAttribute("registeredPlaceIds",favorite==null?java.util.Set.of():favorite.registeredPlaceIds(login(principal)));
        // 초안을 만들 때 찾아 둔 관광지 사진(관광공사). 조회하지 못해도 일정 상세는 그대로 보여 준다.
        var visitIds=itinerary.days().stream().flatMap(d->d.items().stream()).filter(i->"VISIT".equals(i.type()) && i.placeId()!=null)
            .map(com.travel.travel_recommendation.dto.SavedItinerary.Item::placeId).toList();
        var photo=photos.getIfAvailable();
        java.util.Map<Long,?> photoByPlace=java.util.Map.of();
        try { if(photo!=null && !visitIds.isEmpty()) photoByPlace=photo.photos(visitIds); } catch(RuntimeException error) { photoByPlace=java.util.Map.of(); }
        model.addAttribute("photoByPlace",photoByPlace);
        // 숙소(모든 숙박 항목)와 같은 날짜에 수량이 있는 관광시설은 예약(체험)으로 이어 준다(SFR-13-01).
        var reservation=reservations.getIfAvailable();
        java.util.List<com.travel.travel_recommendation.dto.VirtualReservationView.Bookable> bookables=java.util.List.of();
        // SQL 013·016을 적용하지 않은 PC에서도 일정 상세는 그대로 보여 준다.
        // 저장 당시 찾아 둔 숙소 참고 가격은 그 숙소의 객실을 처음 만들 때 기준가로 쓴다.
        var referencePrices=new java.util.HashMap<Long,Integer>();
        for(var quote:itinerary.prices())
            if("ACCOMMODATION".equals(quote.kind()) && quote.found() && quote.placeId()!=null && quote.minWon()!=null)
                referencePrices.put(quote.placeId(),quote.minWon());
        try { if(reservation!=null) bookables=reservation.bookable(id,login(principal),referencePrices); }
        catch(org.springframework.dao.DataAccessException missingTables) { bookables=java.util.List.of(); }
        model.addAttribute("bookables",bookables);
        // 일정표의 장소 옆에 예약 버튼을 붙일 수 있게 '일차-장소 ID'로 찾는 표를 함께 넘긴다.
        var bookableByItem=new java.util.HashMap<String,Object>();
        for(var b:bookables) bookableByItem.putIfAbsent(b.dayNo()+"-"+b.itemPlaceId(),b);
        model.addAttribute("bookableByItem",bookableByItem);
        return "my/itinerary";
    }

    /** 저장한 대중교통 일정 지도의 '실제 경로 보기'(SFR-11-03). 본인 일정만 조회하며, 경로는 저장하지 않고 요청 때마다 조회한다. */
    @GetMapping("/{id}/day-paths")
    @ResponseBody
    org.springframework.http.ResponseEntity<?> dayPaths(@PathVariable long id, @RequestParam int day, Principal principal, HttpSession session) {
        var itinerary=service().detail(id,login(principal));
        if("자동차".equals(itinerary.transportName()))
            return org.springframework.http.ResponseEntity.badRequest().cacheControl(org.springframework.http.CacheControl.noStore())
                .body(java.util.Map.of("message","대중교통 일정에서만 실제 경로를 볼 수 있어요."));
        var route=com.travel.travel_recommendation.service.TripMap.route(itinerary.days().stream()
            .map(d->new com.travel.travel_recommendation.service.TripMap.Day(d.number(),d.items().stream()
                .map(i->new com.travel.travel_recommendation.service.TripMap.Stop(i.type(),i.name(),i.mapUrl())).toList())).toList(),false);
        // 초안 화면과 같은 세션 쿨다운(10초)을 써서 카카오 경로 조회가 연달아 나가지 않게 한다.
        var wait=ItineraryController.dayPathsCooldown(session);
        if(wait!=null) return wait;
        return ItineraryController.dayPathsOf(route,day,transitRoutes.getIfAvailable());
    }

    /** 완성된 수정안을 이 일정에 덮어쓴다. version이 다르면 다른 곳에서 먼저 바뀐 것으로 보고 거부한다. */
    @PostMapping("/{id}/apply")
    String apply(@PathVariable long id, @RequestParam UUID jobId, @RequestParam String version, Principal principal, RedirectAttributes redirect) {
        service().apply(id,jobId,version,login(principal));
        redirect.addFlashAttribute("savedMessage","수정안을 이 일정에 적용했어요. 바뀐 부분은 아래에서 확인하고, 마음에 들지 않으면 수정 전으로 되돌릴 수 있어요.");
        return "redirect:/my/itineraries/"+id;
    }

    /** 가장 최근에 적용한 수정을 되돌린다. 되돌린 뒤 한 번 더 누르면 되돌리기를 취소한다. */
    @PostMapping("/{id}/undo")
    String undo(@PathVariable long id, @RequestParam String version, Principal principal, RedirectAttributes redirect) {
        boolean undone=service().undo(id,version,login(principal));
        redirect.addFlashAttribute("savedMessage",undone?"수정 전 일정으로 되돌렸어요.":"되돌리기를 취소하고 수정한 일정으로 돌아왔어요.");
        return "redirect:/my/itineraries/"+id;
    }

    /** 수정할 초안으로 이동한다. 원래 초안이 없으면 저장한 일정으로 다시 만든다. */
    @PostMapping("/{id}/edit")
    String edit(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        var edit=edits.getIfAvailable();
        if(edit==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"내 일정은 DB 연결 후 이용할 수 있어요.");
        var draft=edit.prepare(id,login(principal));
        if(!draft.missingPlaces().isEmpty()) redirect.addFlashAttribute("editNotice",
            "지금 확인된 관광지 후보에 없는 곳이 있어요: "+String.join(", ",draft.missingPlaces())+". 수정안을 만들 때 이 관광지는 바꿔 주세요.");
        return "redirect:/travel/recommendations/itineraries/"+draft.jobId()+"#revise-title";
    }

    @PostMapping("/{id}/title")
    String rename(@PathVariable long id, @RequestParam String title, Principal principal, RedirectAttributes redirect) {
        service().rename(id,login(principal),title);
        redirect.addFlashAttribute("savedMessage","일정 이름을 바꿨어요.");
        return "redirect:/my/itineraries/"+id;
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        service().delete(id,login(principal));
        redirect.addFlashAttribute("savedMessage","저장한 일정을 삭제했어요.");
        return "redirect:/my/itineraries";
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class,org.springframework.transaction.TransactionException.class})
    String error(RuntimeException error, jakarta.servlet.http.HttpServletRequest request, HttpServletResponse response, Model model) {
        int status=400; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="저장한 일정을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        // 찾을 수 없음(404)은 돌아가도 같은 오류가 나므로 '이전 화면으로' 링크를 두지 않는다.
        model.addAttribute("backUrl",status==404?null:backUrl(request));
        return "my/itinerary-error";
    }
    private static final java.util.regex.Pattern ITINERARY_ACTION=java.util.regex.Pattern.compile("^/my/itineraries/(\\d+)/(apply|title|undo|edit)$");
    /**
     * 실패한 요청에 따라 '이전 화면으로' 돌아갈 주소를 정한다.
     * 일정 저장·수정안 적용은 그 초안 화면, 이름 바꾸기·되돌리기·수정 준비는 그 일정 상세로 돌아간다. 그 밖에는 없다(null).
     */
    static String backUrl(jakarta.servlet.http.HttpServletRequest request) {
        String path=request.getRequestURI().substring(request.getContextPath().length());
        var action=ITINERARY_ACTION.matcher(path);
        boolean matched=action.matches();
        if(path.equals("/my/itineraries") || (matched && action.group(2).equals("apply"))) {
            // 초안 번호(jobId)가 올바른 형식일 때만 주소를 만든다.
            try { return "/travel/recommendations/itineraries/"+UUID.fromString(request.getParameter("jobId")); }
            catch(RuntimeException badJobId) { return null; }
        }
        return matched?"/my/itineraries/"+action.group(1):null;
    }
    private SavedItineraryService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"내 일정은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
