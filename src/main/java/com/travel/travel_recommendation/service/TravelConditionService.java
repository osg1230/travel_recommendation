package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.dto.TravelKeyword;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class TravelConditionService {
    public TravelConditionForm createDraft(TravelConditionForm form, Map<String, List<TravelKeyword>> choices) {
        // 검증한 입력 방법의 값만 세션에 보관한다. DB 저장이나 AI 분석은 아직 수행하지 않는다.
        TravelConditionForm draft = new TravelConditionForm();
        draft.setInputMode(form.getInputMode());
        if (form.getInputMode().equals("TEXT")) {
            draft.setOriginalText(form.getOriginalText());
        } else {
            draft = form;
            draft.setOriginalText("");
            draft.setThemes(form.getThemes().stream().distinct().toList());
            Map<String, List<String>> selected = Map.of("region", List.of(form.getRegion()),
                    "season", List.of(form.getSeason()), "companion", List.of(form.getCompanion()),
                    "transportation", List.of(form.getTransportation()), "themes", draft.getThemes());
            List<TravelKeyword> snapshots = new ArrayList<>();
            selected.forEach((field, values) -> values.forEach(value -> choices.get(field).stream()
                    .filter(keyword -> keyword.getSelectionValue().equals(value)).findFirst().ifPresent(snapshots::add)));
            draft.setSelectedKeywords(List.copyOf(snapshots));
            if (!form.getStartDate().isEmpty()) {
                draft.setDurationDays(String.valueOf(ChronoUnit.DAYS.between(
                        LocalDate.parse(form.getStartDate()), LocalDate.parse(form.getEndDate())) + 1));
            }
        }
        return draft;
    }
}
