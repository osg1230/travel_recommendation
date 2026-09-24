package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.PlaceCandidateView;
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
    private final ObjectProvider<com.travel.travel_recommendation.service.PlaceRatingService> ratings;

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
        String login = principal == null ? null : principal.getName();
        byte[] guest = RecommendationJobController.guestHash(session, false);
        var places = service.find(id, destinationId, login, guest);
        // 저장된 후보가 일정을 만들기에 부족하면(예: 음식점 1곳뿐) 사용자가 버튼을 찾지 않아도 카카오에서 한 번 더 찾아 온다.
        // 같은 추천·지역에서는 세션당 한 번만 자동으로 조회하고, 실패해도 기존 후보로 화면을 보여 준다.
        var discovery = discoveries.getIfAvailable();
        String autoKey = "autoPlaceLookup:" + id + ":" + destinationId;
        if (discovery != null && places.tooFewForSchedule() && session.getAttribute(autoKey) == null) {
            session.setAttribute(autoKey, Boolean.TRUE);
            try {
                int count = discovery.refresh(id, destinationId, login, guest);
                if (count > 0) {
                    places = service.find(id, destinationId, login, guest);
                    if (!model.containsAttribute("lookupMessage"))
                        model.addAttribute("lookupMessage", "후보가 적어 카카오에서 " + count + "곳을 더 찾아 함께 보여 드려요.");
                }
            } catch (RuntimeException lookupFailed) {
                if (!model.containsAttribute("lookupMessage"))
                    model.addAttribute("lookupMessage", "장소를 더 찾아보지 못했어요. 지금 확인된 후보만 보여 드려요.");
            }
        }
        model.addAttribute("places", places);
        // 여행 목적 시설(예: 아쿠아리움)은 목록에서 따로 표시한다.
        model.addAttribute("purpose", service.purpose(id, destinationId,
            principal == null ? null : principal.getName(), RecommendationJobController.guestHash(session, false)));
        // 관광지·숙소 위치를 지도에 표시한다(SFR-11-01).
        model.addAttribute("mapJson", mapper.writeValueAsString(com.travel.travel_recommendation.service.TripMap.places(places)));
        // 장소별 평균 별점·평가 수·많이 고른 태그(SFR-10-02). 비회원도 볼 수 있다.
        var rating = ratings.getIfAvailable();
        var ids = new java.util.ArrayList<Long>();
        places.attractions().forEach(p -> ids.add(p.placeId())); places.restaurants().forEach(p -> ids.add(p.placeId()));
        places.accommodations().forEach(p -> ids.add(p.placeId()));
        model.addAttribute("ratingSummaries", rating == null ? java.util.Map.of() : rating.summaries(ids));
        // 즐겨찾기는 회원 기능이라 로그인 여부와 이미 등록한 장소만 화면에 전달한다.
        var favorites = favoriteServices.getIfAvailable();
        model.addAttribute("loggedIn", principal != null);
        model.addAttribute("favoritePlaceIds", favorites == null ? java.util.Set.of()
            : favorites.registeredPlaceIds(principal == null ? null : principal.getName()));
        return "travel/place-candidates";
    }

    /** 여러 끼 식사와 숙소를 고를 만큼 후보가 있는지. 부족하면 같은 식당·숙소가 반복된다. */

    @ExceptionHandler(ResponseStatusException.class)
    String unavailable(ResponseStatusException error, HttpServletResponse response, Model model) {
        response.setStatus(error.getStatusCode().value());
        response.setHeader("Cache-Control", "no-store");
        model.addAttribute("message", error.getReason());
        model.addAttribute("notFound", error.getStatusCode().value() == 404);
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
