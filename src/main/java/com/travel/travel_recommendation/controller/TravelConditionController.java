package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.dto.TravelKeyword;
import org.springframework.dao.DataAccessException;
import com.travel.travel_recommendation.service.TravelConditionService;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import com.travel.travel_recommendation.service.TravelConditionValidator;
import jakarta.servlet.http.HttpSession;
import java.util.Map;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;

@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/conditions")
public class TravelConditionController {
    public static final String DRAFT_KEY = "travelConditionDraft";
    /** 취향 탐색 결과로 미리 채운 입력값. 검증 전이므로 초안(DRAFT_KEY)으로 쓰지 않는다. */
    public static final String PREFILL_KEY = "travelConditionPrefill";
    private final TravelConditionOptions options;
    private final TravelConditionValidator validator;
    private final TravelConditionService service;

    @InitBinder("travelForm")
    void bindForm(WebDataBinder binder) {
        binder.setAllowedFields("inputMode", "originalText", "region", "startDate", "endDate",
                "durationDays", "season", "companion", "themes", "budget", "transportation", "moods", "pace");
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
    }

    @ModelAttribute("choices")
    Map<String, List<TravelKeyword>> choices(Model model) {
        model.addAttribute("preview", options.isPreview());
        model.addAttribute("choicesUnavailable", false);
        try {
            Map<String, List<TravelKeyword>> choices = options.getChoices();
            model.addAttribute("choicesEmpty", choices.values().stream().anyMatch(List::isEmpty));
            return choices;
        } catch (DataAccessException ex) {
            // DB 장애를 예시 목록으로 숨기지 않는다. 문장 입력과 기존 확인 화면은 계속 제공한다.
            model.addAttribute("choicesUnavailable", true);
            Map<String, List<TravelKeyword>> empty = new java.util.LinkedHashMap<>();
            TravelConditionOptions.CATEGORY_CODES.keySet().forEach(field -> empty.put(field, List.of()));
            return empty;
        }
    }

    @GetMapping
    String input(HttpSession session, Model model) {
        Object prefill = session.getAttribute(PREFILL_KEY);
        Object draft = prefill != null ? prefill : session.getAttribute(DRAFT_KEY);
        model.addAttribute("travelForm", draft != null ? draft : new TravelConditionForm());
        model.addAttribute("inputToken", issueInputToken(session));
        return "travel/conditions";
    }

    /** 입력 화면(조건 입력·홈 문장 입력)마다 새 입력 토큰을 세션에 둔다. 분석 중이면 진행 중인 토큰을 유지한다. */
    static String issueInputToken(HttpSession session) {
        String token = session.getAttribute("travelConditionAnalyzing") != null
            && session.getAttribute("travelInputToken") instanceof String existing ? existing : java.util.UUID.randomUUID().toString();
        session.setAttribute("travelInputToken", token);
        return token;
    }

    @PostMapping
    String check(@ModelAttribute("travelForm") TravelConditionForm form, BindingResult result, HttpSession session,
                 @ModelAttribute(value = "choices", binding = false) Map<String, List<TravelKeyword>> choices, Model model) {
        validator.validate(form, result, choices);
        if ("KEYWORD".equals(form.getInputMode()) && Boolean.TRUE.equals(model.getAttribute("choicesUnavailable"))) {
            result.reject("unavailable", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
        if (result.hasErrors()) return "travel/conditions";
        TravelConditionForm draft = service.createDraft(form, choices);
        synchronized (session) {
            session.setAttribute(DRAFT_KEY, draft);
            session.removeAttribute(PREFILL_KEY);
            session.removeAttribute(TravelConditionReviewController.REVIEW_KEY);
            session.removeAttribute(TravelConditionReviewController.CONFIRMED_KEY);
            session.removeAttribute("travelDirectRecommendation");
        }
        return "redirect:/travel/conditions/check";
    }

    @GetMapping("/check")
    String confirmation(HttpSession session, Model model) {
        Object draft = session.getAttribute(DRAFT_KEY);
        if (draft == null) return "redirect:/travel/conditions";
        model.addAttribute("travelForm", draft);
        model.addAttribute("discoveredThemes", session.getAttribute(PreferenceDiscoveryController.APPLIED_KEY));
        return "travel/condition-check";
    }
}
