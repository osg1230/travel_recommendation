package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;
import org.springframework.validation.Validator;

@Component
@RequiredArgsConstructor
public class TravelConditionValidator implements Validator {
    private final TravelConditionOptions options;

    @Override
    public boolean supports(Class<?> type) { return TravelConditionForm.class.equals(type); }

    @Override
    public void validate(Object target, Errors errors) {
        TravelConditionForm form = (TravelConditionForm) target;
        if (!List.of("TEXT", "KEYWORD").contains(form.getInputMode())) {
            errors.rejectValue("inputMode", "invalid", "입력 방법을 선택해 주세요.");
            return;
        }
        // 현재 선택한 입력 방법만 검증한다. 다른 탭의 값은 추천 조건에 섞지 않는다.
        if (form.getInputMode().equals("TEXT")) {
            if (form.getOriginalText().isBlank() || form.getOriginalText().length() > 2000) {
                errors.rejectValue("originalText", "invalid", "원하는 여행을 1~2,000자로 적어 주세요.");
            }
            return;
        }
        checkChoice("region", form.getRegion(), errors);
        checkChoice("season", form.getSeason(), errors);
        checkChoice("companion", form.getCompanion(), errors);
        checkChoice("transportation", form.getTransportation(), errors);
        if (form.getThemes().size() > options.getChoices().get("themes").size()
                || form.getThemes().stream().anyMatch(value -> !options.contains("themes", value))) {
            errors.rejectValue("themes", "invalid", "여행 취향은 화면에 있는 항목에서 선택해 주세요.");
        }
        LocalDate start = parseDate("startDate", form.getStartDate(), errors);
        LocalDate end = parseDate("endDate", form.getEndDate(), errors);
        if (form.getStartDate().isEmpty() != form.getEndDate().isEmpty()) {
            errors.rejectValue("endDate", "incomplete", "출발일과 마지막 날을 함께 입력해 주세요.");
        }
        Integer days = null;
        if (!form.getDurationDays().isEmpty()) {
            try {
                days = Integer.valueOf(form.getDurationDays());
                if (days < 1 || days > 32767) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                errors.rejectValue("durationDays", "invalid", "여행 일수는 1~32,767 사이의 정수로 입력해 주세요.");
            }
        }
        if (start != null && end != null) {
            long dateDays = ChronoUnit.DAYS.between(start, end) + 1;
            if (dateDays < 1 || dateDays > 32767) {
                errors.rejectValue("endDate", "invalid", "마지막 날은 출발일 이후로, 여행 기간은 32,767일 이내로 입력해 주세요.");
            } else if (days != null && days != dateDays) {
                errors.rejectValue("durationDays", "mismatch", "입력한 날짜와 여행 일수가 달라요. 날짜를 사용하려면 여행 일수를 비워 주세요.");
            }
        }
        if (!form.getBudget().isEmpty()) {
            try {
                if (!form.getBudget().matches("[0-9]{1,10}(\\.[0-9]{1,2})?")) throw new NumberFormatException();
                BigDecimal budget = new BigDecimal(form.getBudget());
                if (budget.compareTo(new BigDecimal("9999999999.99")) > 0) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                errors.rejectValue("budget", "invalid", "예산은 0~9,999,999,999.99원 사이의 숫자로 입력해 주세요. 쉼표는 빼 주세요.");
            }
        }
    }

    private void checkChoice(String field, String value, Errors errors) {
        if (!value.isEmpty() && !options.contains(field, value)) {
            errors.rejectValue(field, "invalid", "화면에 있는 항목에서 선택해 주세요.");
        }
    }

    private LocalDate parseDate(String field, String value, Errors errors) {
        if (value.isEmpty()) return null;
        try {
            LocalDate date = LocalDate.parse(value);
            if (date.getYear() < 1 || date.getYear() > 9999) throw new DateTimeParseException("range", value, 0);
            return date;
        } catch (DateTimeParseException ex) {
            errors.rejectValue(field, "invalid", "올바른 날짜를 입력해 주세요.");
            return null;
        }
    }
}
