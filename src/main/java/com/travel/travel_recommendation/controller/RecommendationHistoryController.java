package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.RecommendationHistoryService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/** 회원의 추천 이력 목록·상세·삭제 화면(SFR-14-02~04). */
@Controller
@RequiredArgsConstructor
@RequestMapping("/my/recommendations")
public class RecommendationHistoryController {
    private final ObjectProvider<RecommendationHistoryService> services;
    /** 다시 추천은 조건 확인 흐름(지금 선택지로 다시 검증 → 확정 → 추천 작업)을 그대로 쓴다. */
    private final TravelConditionReviewController reviews;

    @GetMapping
    String list(Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("histories",service().list(principal.getName()));
        return "my/recommendations";
    }

    @GetMapping("/{id}")
    String detail(@PathVariable long id, Principal principal, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control","no-store");
        model.addAttribute("history",service().detail(id,principal.getName()));
        return "my/recommendation";
    }

    /**
     * 같은 조건으로 다시 추천(SFR-14-03, UC-17 기본 시나리오 5). 본인 이력인지 확인하고 그때 확정한 조건을 읽은 뒤,
     * 지금 선택지로 다시 검증해 새 추천 작업을 만든다. 지난 출발일처럼 지금은 쓸 수 없는 값이 있으면 조건 수정 화면으로 보낸다.
     */
    @PostMapping("/{id}/again")
    String again(@PathVariable long id, Principal principal, HttpServletRequest request, HttpSession session,
                 RedirectAttributes redirect) {
        var conditions=service().conditions(id,principal.getName());
        return reviews.recommendAgain(conditions,principal,request,session,redirect,"/my/recommendations/"+id);
    }

    @PostMapping("/{id}/delete")
    String delete(@PathVariable long id, Principal principal, RedirectAttributes redirect) {
        service().delete(id,principal.getName());
        redirect.addFlashAttribute("savedMessage","추천 이력을 삭제했어요. 저장한 일정은 그대로 남아 있어요.");
        return "redirect:/my/recommendations";
    }

    @ExceptionHandler({ResponseStatusException.class,DataAccessException.class})
    String error(RuntimeException error, HttpServletResponse response, Model model) {
        int status; String message;
        if(error instanceof ResponseStatusException e) { status=e.getStatusCode().value(); message=e.getReason(); }
        else { status=503; message="추천 이력을 불러오지 못했어요. 잠시 후 다시 시도해 주세요."; }
        response.setStatus(status); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",message);
        return "my/recommendation-error";
    }
    private RecommendationHistoryService service() {
        var service=services.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"추천 이력은 DB 연결 후 이용할 수 있어요.");
        return service;
    }
}
