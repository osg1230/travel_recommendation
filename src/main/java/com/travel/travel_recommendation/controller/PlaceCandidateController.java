package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.service.PlaceCandidateService;
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
