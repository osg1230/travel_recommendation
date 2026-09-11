package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import com.travel.travel_recommendation.service.TravelConditionValidator;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
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
    private static final String DRAFT_KEY = "travelConditionDraft";
    private final TravelConditionOptions options;
    private final TravelConditionValidator validator;

    @InitBinder("travelForm")
    void bindForm(WebDataBinder binder) {
        binder.setAllowedFields("inputMode", "originalText", "region", "startDate", "endDate",
                "durationDays", "season", "companion", "themes", "budget", "transportation");
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
    }

    @ModelAttribute("choices")
    Map<String, List<String>> choices() { return options.getChoices(); }

    @GetMapping
    String input(HttpSession session, Model model) {
        Object draft = session.getAttribute(DRAFT_KEY);
        model.addAttribute("travelForm", draft != null ? draft : new TravelConditionForm());
        return "travel/conditions";
    }

    @PostMapping
    String check(@ModelAttribute("travelForm") TravelConditionForm form, BindingResult result, HttpSession session) {
        validator.validate(form, result);
        if (result.hasErrors()) return "travel/conditions";
        // 검증한 입력 방법의 값만 세션에 보관한다. DB 저장이나 AI 분석은 아직 수행하지 않는다.
        TravelConditionForm draft = new TravelConditionForm();
        draft.setInputMode(form.getInputMode());
        if (form.getInputMode().equals("TEXT")) {
            draft.setOriginalText(form.getOriginalText());
        } else {
            draft = form;
            draft.setOriginalText("");
            draft.setThemes(form.getThemes().stream().distinct().toList());
            if (!form.getStartDate().isEmpty()) {
                draft.setDurationDays(String.valueOf(ChronoUnit.DAYS.between(
                        LocalDate.parse(form.getStartDate()), LocalDate.parse(form.getEndDate())) + 1));
            }
        }
        session.setAttribute(DRAFT_KEY, draft);
        return "redirect:/travel/conditions/check";
    }

    @GetMapping("/check")
    String confirmation(HttpSession session, Model model) {
        Object draft = session.getAttribute(DRAFT_KEY);
        if (draft == null) return "redirect:/travel/conditions";
        model.addAttribute("travelForm", draft);
        return "travel/condition-check";
    }
}
