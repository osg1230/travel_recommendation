package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.PlaceCandidateService;
import com.travel.travel_recommendation.service.PlaceDiscoveryService;
import com.travel.travel_recommendation.service.RecommendationException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
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

@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/recommendations")
public class PlaceCandidateController {
    private final ObjectProvider<PlaceCandidateService> services;
    private final ObjectProvider<PlaceDiscoveryService> discoveries;

    @PostMapping("/{id}/destinations/{destinationId}/places/refresh")
    String refresh(@PathVariable UUID id,@PathVariable long destinationId,Principal principal,HttpSession session,
                   HttpServletResponse response,RedirectAttributes flash) {
        response.setHeader("Cache-Control","no-store");
        var service=discoveries.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"장소 조회는 DB 연결 후 이용할 수 있어요.");
        synchronized(session) {
            String key="placeLookup:"+id+":"+destinationId;
            var previous=(String)session.getAttribute("lastPlaceLookup");
            var time=(Long)session.getAttribute("lastPlaceLookupTime");
            if(!key.equals(previous) || time==null || System.currentTimeMillis()-time>=60000) {
                int count=service.refresh(id,destinationId,principal==null?null:principal.getName(),RecommendationJobController.guestHash(session,false));
                session.setAttribute("lastPlaceLookup",key); session.setAttribute("lastPlaceLookupTime",System.currentTimeMillis());
                flash.addFlashAttribute("lookupMessage",count==0?"이번 조회에서 추가로 확인할 수 있는 장소가 없어요. 기존 후보를 유지했어요.":"카카오에서 "+count+"곳의 정보를 확인했어요. 기존 후보와 함께 표시해요.");
            } else flash.addFlashAttribute("lookupMessage","방금 확인한 결과를 표시해요. 새 조회는 1분 후 가능해요.");
        }
        response.setStatus(303);
        return "redirect:/travel/recommendations/"+id+"/destinations/"+destinationId+"/places";
    }

    @ExceptionHandler(RecommendationException.class)
    String lookupFailed(RecommendationException error,HttpServletResponse response,Model model) {
        response.setStatus(503); response.setHeader("Cache-Control","no-store");
        model.addAttribute("message",error.getMessage()+" 기존 장소 정보는 유지했어요.");
        return "travel/place-candidates-error";
    }

    @GetMapping("/{id}/destinations/{destinationId}/places")
    String page(@PathVariable UUID id, @PathVariable long destinationId, Principal principal,
            HttpSession session, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("jobId", id);
        var service = services.getIfAvailable();
        if (service == null) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                "장소 정보를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.");
        }
        model.addAttribute("places", service.find(id, destinationId,
            principal == null ? null : principal.getName(), RecommendationJobController.guestHash(session, false)));
        return "travel/place-candidates";
    }

    @ExceptionHandler(ResponseStatusException.class)
    String unavailable(ResponseStatusException error, HttpServletResponse response, Model model) {
        response.setStatus(error.getStatusCode().value());
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("message", error.getReason());
        return "travel/place-candidates-error";
    }

    @ExceptionHandler(DataAccessException.class)
    String databaseUnavailable(HttpServletResponse response, Model model) {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("message", "장소 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        return "travel/place-candidates-error";
    }
}
