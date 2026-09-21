package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.SavedItineraryService;
import jakarta.servlet.http.HttpServletResponse;
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
    private final ObjectProvider<com.travel.travel_recommendation.service.PlaceRatingService> ratings;
    private final tools.jackson.databind.ObjectMapper mapper;

    @PostMapping
    String save(@RequestParam UUID jobId, Principal principal, RedirectAttributes redirect) {
        var saved=service().save(jobId,login(principal));
        redirect.addFlashAttribute("savedMessage",saved.created()
            ?"내 일정으로 저장했어요. 이름은 아래에서 바꿀 수 있어요."
            :"이미 저장한 일정이에요. 저장해 둔 내용을 보여 드릴게요.");
        return "redirect:/my/itineraries/"+saved.id();
    }

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("itineraries",service().list(login(principal)));
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
        // 다녀온 장소마다 평가 요약과 평가하기 링크를 보여 준다(SFR-10).
        var rating=ratings.getIfAvailable();
        model.addAttribute("ratingSummaries",rating==null?java.util.Map.of():rating.summaries(itinerary.days().stream()
            .flatMap(d->d.items().stream()).filter(i->i.placeId()!=null && java.util.Set.of("VISIT","MEAL","STAY").contains(i.type()))
            .map(com.travel.travel_recommendation.dto.SavedItinerary.Item::placeId).toList()));
        return "my/itinerary";
    }

    /** 완성된 수정안을 이 일정에 덮어쓴다. version이 다르면 다른 곳에서 먼저 바뀐 것으로 보고 거부한다. */
    @PostMapping("/{id}/apply")
    String apply(@PathVariable long id, @RequestParam UUID jobId, @RequestParam String version, Principal principal, RedirectAttributes redirect) {
        service().apply(id,jobId,version,login(principal));
        redirect.addFlashAttribute("savedMessage","수정안을 이 일정에 적용했어요.");
        return "redirect:/my/itineraries/"+id;
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

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status=400; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="저장한 일정을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        return "my/itinerary-error";
    }
    private SavedItineraryService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"내 일정은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
    private String login(Principal principal) { return principal==null?null:principal.getName(); }
}
