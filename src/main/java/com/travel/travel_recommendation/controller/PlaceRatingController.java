package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.PlaceRatingService;
import jakarta.servlet.http.HttpServletResponse;
import java.security.Principal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 장소 평가 화면(SFR-10-01·03·04)과 내 평가 목록. 회원만 이용한다. */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/ratings")
public class PlaceRatingController {
    private final ObjectProvider<PlaceRatingService> services;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("ratings",service().mine(principal.getName()));
        return "my/ratings";
    }

    @GetMapping("/{placeId}")
    String form(@PathVariable long placeId, @RequestParam(required=false) String returnTo, Principal principal,
                HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("form",service().form(placeId,principal.getName()));
        model.addAttribute("maxTags",PlaceRatingService.MAX_TAGS);
        model.addAttribute("returnTo",safe(returnTo));
        return "my/rating";
    }

    @PostMapping("/{placeId}")
    String save(@PathVariable long placeId, @RequestParam(required=false) Integer score, @RequestParam(required=false) List<Long> tags,
                @RequestParam(required=false) String returnTo, Principal principal, RedirectAttributes redirect) {
        boolean created=service().save(placeId,principal.getName(),score,tags);
        redirect.addFlashAttribute("savedMessage",created?"평가를 등록했어요.":"평가를 새로 고른 내용으로 바꿨어요.");
        return "redirect:"+(safe(returnTo)!=null?safe(returnTo):"/my/ratings/"+placeId);
    }

    @PostMapping("/{placeId}/delete")
    String delete(@PathVariable long placeId, Principal principal, RedirectAttributes redirect) {
        service().delete(placeId,principal.getName());
        redirect.addFlashAttribute("savedMessage","평가를 삭제했어요.");
        return "redirect:/my/ratings";
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="평가를 처리하지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        return "my/rating-error";
    }
    // 돌아갈 주소는 이 서비스의 저장 일정·장소 목록 경로만 허용해 외부로 이동하지 않는다.
    private static String safe(String returnTo) {
        if(returnTo==null) return null;
        return returnTo.matches("^/my/itineraries/\\d+$") || returnTo.matches("^/travel/recommendations/[0-9a-fA-F-]{36}/destinations/\\d+/places$")
            ? returnTo : null;
    }
    private PlaceRatingService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"장소 평가는 DB 연결 후 이용할 수 있어요.");
        return service;
    }
}
