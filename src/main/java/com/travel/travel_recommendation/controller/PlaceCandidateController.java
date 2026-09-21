package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.PlaceCandidateService;
import com.travel.travel_recommendation.service.PlaceDiscoveryService;
import com.travel.travel_recommendation.service.PlaceOperatingService;
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
    private final ObjectProvider<PlaceOperatingService> operatingServices;
    private final ObjectProvider<com.travel.travel_recommendation.service.FavoriteService> favoriteServices;
    private final tools.jackson.databind.ObjectMapper mapper;

    @PostMapping("/{id}/destinations/{destinationId}/places/{placeId}/operating-info")
    @ResponseStatus(HttpStatus.SEE_OTHER)
    String operatingInfo(@PathVariable UUID id,@PathVariable long destinationId,@PathVariable long placeId,
            Principal principal,HttpSession session,HttpServletResponse response,RedirectAttributes flash) {
        response.setHeader("Cache-Control","no-store");
        var service=operatingServices.getIfAvailable();
        if(service==null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"운영정보 조회는 DB 연결 후 이용할 수 있어요.");
        byte[] guest;
        boolean allowed;
        // 외부 조회 전에 다음 조회 시각을 먼저 잡아 두고, 느린 호출은 세션 잠금 밖에서 한다.
        synchronized(session) {
            var last=(Long)session.getAttribute("lastOperatingLookupTime");
            allowed=last==null || System.currentTimeMillis()-last>=60000;
            if(allowed) session.setAttribute("lastOperatingLookupTime",System.currentTimeMillis());
            guest=RecommendationJobController.guestHash(session,false);
        }
        if(!allowed) {
            flash.addFlashAttribute("lookupMessage","새 운영정보 조회는 1분 후 가능해요.");
        } else {
            boolean found=service.refresh(id,destinationId,placeId,principal==null?null:principal.getName(),guest);
            flash.addFlashAttribute("lookupMessage",found?"관광공사의 운영정보를 표시해요. 방문 가능 여부는 일정의 날짜·시간과 별도로 대조해요.":"같은 장소의 운영정보를 확실하게 연결하지 못했어요. 출처에서 직접 확인해 주세요.");
        }
        response.setStatus(303);
        return "redirect:/travel/recommendations/"+id+"/destinations/"+destinationId+"/places";
    }

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
        var places = service.find(id, destinationId,
            principal == null ? null : principal.getName(), RecommendationJobController.guestHash(session, false));
        model.addAttribute("places", places);
        // 관광지·숙소 위치를 지도에 표시한다(SFR-11-01).
        model.addAttribute("mapJson", mapper.writeValueAsString(com.travel.travel_recommendation.service.TripMap.places(places)));
        // 즐겨찾기는 회원 기능이라 로그인 여부와 이미 등록한 장소만 화면에 전달한다.
        var favorites = favoriteServices.getIfAvailable();
        model.addAttribute("loggedIn", principal != null);
        model.addAttribute("favoritePlaceIds", favorites == null ? java.util.Set.of()
            : favorites.registeredPlaceIds(principal == null ? null : principal.getName()));
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
