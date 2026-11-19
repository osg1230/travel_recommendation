package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import com.travel.travel_recommendation.dto.TravelKeyword;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.validation.Errors;

@Component
@RequiredArgsConstructor
public class TravelConditionValidator {
    /** 일정은 최대 7일까지 만든다(일정 입력 화면과 같은 기준). 추천 단계에서도 이 범위의 여행만 받는다. */
    public static final int MAX_DAYS = 7;
    private static final List<String> SEASON_NAMES = List.of("봄", "여름", "가을", "겨울");

    public void validate(TravelConditionForm form, Errors errors, Map<String, List<TravelKeyword>> choices) {
        validate(form, errors, choices, false);
    }

    public void validate(TravelConditionForm form, Errors errors, Map<String, List<TravelKeyword>> choices, boolean freeRegion) {
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
        if (freeRegion) {
            if (form.getRegion().length() > 100) errors.rejectValue("region", "length", "지역은 100자 이내로 입력해 주세요.");
        } else {
            checkChoice("region", form.getRegion(), errors, choices);
        }
        checkChoice("season", form.getSeason(), errors, choices);
        checkChoice("companion", form.getCompanion(), errors, choices);
        checkChoice("transportation", form.getTransportation(), errors, choices);
        if (form.getThemes().size() > 100
                || form.getThemes().stream().anyMatch(value -> !contains(choices, "themes", value))) {
            errors.rejectValue("themes", "invalid", "여행 취향은 화면에 있는 항목에서 선택해 주세요.");
        }
        if (form.getMoods().size() > 100
                || form.getMoods().stream().anyMatch(value -> !contains(choices, "moods", value))) {
            errors.rejectValue("moods", "invalid", "여행 분위기는 화면에 있는 항목에서 선택해 주세요.");
        }
        checkChoice("pace", form.getPace(), errors, choices);
        LocalDate start = parseDate("startDate", form.getStartDate(), errors);
        LocalDate end = parseDate("endDate", form.getEndDate(), errors);
        // 지난 날짜로는 일정을 만들 수 없다. 일정 입력 화면(ItineraryController)과 같이 한국 날짜로 오늘부터 받는다.
        if (start != null && start.isBefore(LocalDate.now(KoreaTime.ZONE))) {
            errors.rejectValue("startDate", "past", "출발일은 오늘이나 그 이후 날짜로 골라 주세요.");
        }
        Integer days = null;
        if (!form.getDurationDays().isEmpty()) {
            try {
                days = Integer.valueOf(form.getDurationDays());
                if (days < 1) throw new NumberFormatException();
                if (days > MAX_DAYS) {
                    days = null;
                    errors.rejectValue("durationDays", "tooLong", "여행 일수는 7일까지 정할 수 있어요. 일정은 최대 7일까지 만들어요.");
                }
            } catch (NumberFormatException ex) {
                days = null;
                errors.rejectValue("durationDays", "invalid", "여행 일수는 1~7 사이의 정수로 입력해 주세요.");
            }
        }
        // 마지막 날을 비워 두면 출발일과 여행 일수로 계산해 채운다. 사용자는 출발일과 일수만 고르면 된다.
        if (start != null && form.getEndDate().isEmpty() && days != null) {
            end = start.plusDays(days - 1);
            form.setEndDate(end.toString());
        }
        // 여행 일수에 이미 오류가 있으면 그 안내만 보여 준다(일수를 고치면 마지막 날이 계산된다).
        if (form.getStartDate().isEmpty() != form.getEndDate().isEmpty() && !errors.hasFieldErrors("durationDays")) {
            errors.rejectValue("endDate", "incomplete", form.getStartDate().isEmpty()
                    ? "마지막 날을 정했다면 출발일도 입력해 주세요."
                    : "출발일을 정했다면 여행 일수도 알려 주세요.");
        }
        if (start != null && end != null) {
            long dateDays = ChronoUnit.DAYS.between(start, end) + 1;
            if (dateDays < 1) {
                errors.rejectValue("endDate", "invalid", "마지막 날은 출발일과 같거나 그 이후로 입력해 주세요.");
            } else if (dateDays > MAX_DAYS) {
                errors.rejectValue("endDate", "tooLong", "여행 기간은 7일까지 정할 수 있어요. 출발일과 여행 일수를 다시 확인해 주세요.");
            } else if (days != null && days != dateDays) {
                errors.rejectValue("durationDays", "mismatch", "입력한 날짜와 여행 일수가 달라요. 날짜를 사용하려면 여행 일수를 비워 주세요.");
            }
        }
        checkSeason(form, start, errors, choices);
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

    /**
     * 출발일이 속한 달의 계절과 고른 계절이 다르면 어느 쪽이 맞는지 사용자가 고치게 한다(SFR-02-07 조건 충돌 안내).
     * 출발일에 이미 오류가 있으면(지난 날짜 등) 그 오류를 먼저 고치게 하고 계절은 비교하지 않는다.
     */
    private void checkSeason(TravelConditionForm form, LocalDate start, Errors errors, Map<String, List<TravelKeyword>> choices) {
        if (start == null || form.getSeason().isEmpty() || errors.hasFieldErrors("startDate") || errors.hasFieldErrors("season")) return;
        String chosen = choices.get("season").stream().filter(k -> k.getSelectionValue().equals(form.getSeason()))
                .map(TravelKeyword::name).findFirst().orElse(null);
        String expected = ConditionReviewService.seasonName(start.getMonthValue());
        if (chosen != null && SEASON_NAMES.contains(chosen) && !chosen.equals(expected)) {
            errors.rejectValue("season", "mismatch", "출발일(" + start.getMonthValue() + "월)과 계절(" + chosen
                    + ")이 맞지 않아요. 둘 중 하나를 고쳐 주세요.");
        }
    }

    private boolean contains(Map<String, List<TravelKeyword>> choices, String field, String value) {
        return choices.getOrDefault(field, List.of()).stream().anyMatch(choice -> choice.getSelectionValue().equals(value));
    }

    private void checkChoice(String field, String value, Errors errors, Map<String, List<TravelKeyword>> choices) {
        if (!value.isEmpty() && !contains(choices, field, value)) {
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
