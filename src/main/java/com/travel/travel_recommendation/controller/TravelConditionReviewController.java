package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/conditions")
public class TravelConditionReviewController {
    public static final String REVIEW_KEY = "travelConditionReview";
    public static final String CONFIRMED_KEY = "confirmedTravelConditions";
    private static final String BUSY_KEY = "travelConditionAnalyzing";
    private final ConditionReviewService service;
    private final TravelConditionOptions options;

    private record Review(String sourceId, ConditionReviewForm form, String summary) {}

    @InitBinder("reviewForm")
    void bind(WebDataBinder binder) {
        binder.setAllowedFields("revision", "region", "startDate", "endDate", "durationDays", "budget",
                "season", "companion", "themes", "transportation", "partySize", "accommodationNeeded", "includeNearby");
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
    }

    @PostMapping("/analyze")
    String analyze(@RequestParam String draftId, HttpSession session, RedirectAttributes flash) {
        TravelConditionForm draft;
        synchronized (session) {
            draft = draft(session);
            if (draft == null || !draft.getDraftId().equals(draftId)) return stale(flash);
            if (!"TEXT".equals(draft.getInputMode())) return "redirect:/travel/conditions/review";
            Review existing = (Review) session.getAttribute(REVIEW_KEY);
            if (existing != null && existing.sourceId().equals(draftId)) return "redirect:/travel/conditions/review";
            if (session.getAttribute(BUSY_KEY) != null) {
                flash.addFlashAttribute("analysisError", "문장을 분석 중이에요. 잠시 기다린 뒤 다시 확인해 주세요.");
                return "redirect:/travel/conditions/check";
            }
            session.setAttribute(BUSY_KEY, draftId);
        }
        try {
            // API 응답을 기다릴 때는 세션을 잠그지 않고, 응답 후 입력이 같은지 다시 확인한다.
            var analysis = service.analyze(draft, options.getChoices());
            synchronized (session) {
                if (draft(session) != draft) return stale(flash);
                analysis.form().setRevision(UUID.randomUUID().toString());
                session.setAttribute(REVIEW_KEY, new Review(draftId, analysis.form(), analysis.summary()));
                session.removeAttribute(CONFIRMED_KEY);
            }
            return "redirect:/travel/conditions/review";
        } catch (ConditionAnalysisException ex) {
            flash.addFlashAttribute("analysisError", ex.getMessage());
            return "redirect:/travel/conditions/check";
        } catch (DataAccessException ex) {
            flash.addFlashAttribute("analysisError", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            return "redirect:/travel/conditions/check";
        } catch (IllegalStateException ex) {
            return "redirect:/travel/conditions";
        } finally {
            try {
                synchronized (session) {
                    if (draftId.equals(session.getAttribute(BUSY_KEY))) session.removeAttribute(BUSY_KEY);
                }
            } catch (IllegalStateException ignored) { /* 로그아웃한 세션을 다시 만들지 않는다. */ }
        }
    }

    @GetMapping("/review")
    String review(HttpSession session, Model model) {
        synchronized (session) {
            TravelConditionForm draft = draft(session);
            if (draft == null) return "redirect:/travel/conditions";
            Review review = (Review) session.getAttribute(REVIEW_KEY);
            if (review == null || !review.sourceId().equals(draft.getDraftId())) {
                if ("TEXT".equals(draft.getInputMode())) return "redirect:/travel/conditions/check";
                ConditionReviewForm form = service.fromKeywords(draft);
                form.setRevision(UUID.randomUUID().toString());
                review = new Review(draft.getDraftId(), form, "");
                session.setAttribute(REVIEW_KEY, review);
            }
            model.addAttribute("reviewForm", review.form());
            context(model, draft, review);
        }
        choices(model);
        return "travel/condition-review";
    }

    @PostMapping("/confirm")
    String confirm(@ModelAttribute("reviewForm") ConditionReviewForm form, BindingResult errors,
                   HttpSession session, Model model, RedirectAttributes flash) {
        Map<String, List<TravelKeyword>> choices = choices(model);
        synchronized (session) {
            TravelConditionForm draft = draft(session);
            Review review = (Review) session.getAttribute(REVIEW_KEY);
            if (draft == null || review == null || !review.sourceId().equals(draft.getDraftId())
                    || !review.form().getRevision().equals(form.getRevision())) return stale(flash);
            service.validate(form, errors, choices);
            if (Boolean.TRUE.equals(model.getAttribute("choicesUnavailable")))
                errors.reject("unavailable", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            if (errors.hasErrors()) {
                context(model, draft, review);
                return "travel/condition-review";
            }
            session.setAttribute(CONFIRMED_KEY, service.confirm(form, draft, review.summary(), choices));
            // 확정 때마다 토큰을 교체해 이전 탭이나 중복 제출이 최신 결과를 덮어쓰지 않게 한다.
            form.setRevision(UUID.randomUUID().toString());
            session.setAttribute(REVIEW_KEY, new Review(draft.getDraftId(), form, review.summary()));
        }
        return "redirect:/travel/conditions/confirmed";
    }

    @GetMapping("/confirmed")
    String confirmed(HttpSession session, Model model) {
        Object confirmed = session.getAttribute(CONFIRMED_KEY);
        if (confirmed == null) return "redirect:/travel/conditions";
        model.addAttribute("conditions", confirmed);
        return "travel/condition-confirmed";
    }

    private TravelConditionForm draft(HttpSession session) {
        return (TravelConditionForm) session.getAttribute(TravelConditionController.DRAFT_KEY);
    }
    private void context(Model model, TravelConditionForm draft, Review review) {
        model.addAttribute("originalText", draft.getOriginalText());
        model.addAttribute("analysisSummary", review.summary());
        model.addAttribute("analyzed", "TEXT".equals(draft.getInputMode()));
    }
    private Map<String, List<TravelKeyword>> choices(Model model) {
        model.addAttribute("choicesUnavailable", false);
        try {
            var choices = options.getChoices();
            model.addAttribute("choices", choices);
            return choices;
        } catch (DataAccessException ex) {
            Map<String, List<TravelKeyword>> empty = new java.util.LinkedHashMap<>();
            TravelConditionOptions.CATEGORY_CODES.keySet().forEach(field -> empty.put(field, List.of()));
            model.addAttribute("choicesUnavailable", true);
            model.addAttribute("choices", empty);
            return empty;
        }
    }
    private String stale(RedirectAttributes flash) {
        flash.addFlashAttribute("analysisError", "입력 내용이 바뀌었거나 만료됐어요. 현재 입력 내용을 다시 확인해 주세요.");
        return "redirect:/travel/conditions/check";
    }
}
