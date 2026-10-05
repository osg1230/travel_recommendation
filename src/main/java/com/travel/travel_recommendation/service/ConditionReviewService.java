package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class ConditionReviewService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ConditionReviewService.class);
    private final OpenAiConditionClient client;
    private final TravelConditionValidator validator;
    private static final Set<String> RESPONSE_FIELDS = Set.of("isTravel", "region", "startDate", "endDate",
            "durationDays", "season", "companion", "themes", "budget", "transportation", "partySize",
            "accommodationNeeded", "includeNearby", "summary", "travelPurpose", "suggestedConcept", "excludedRegions");

    public record Analysis(ConditionReviewForm form, String summary) {}

    public Analysis analyze(TravelConditionForm draft, Map<String, List<TravelKeyword>> choices) {
        if (draft.getOriginalText().isBlank() || draft.getOriginalText().length() > 2000) throw invalid();
        JsonNode result = client.analyze(draft.getOriginalText(), choices);
        // 모델이 형식을 지켰다고 가정하지 않고 누락·추가 필드와 각 자료형부터 검사한다.
        if (result == null || !result.isObject() || !result.propertyNames().equals(RESPONSE_FIELDS)) throw invalid();
        if (!result.get("isTravel").isBoolean()) throw invalid();
        if (!result.get("isTravel").booleanValue())
            throw new ConditionAnalysisException("국내 여행 조건을 찾지 못했어요. 가고 싶은 지역이나 여행 목적을 적어 주세요.");
        ConditionReviewForm form = new ConditionReviewForm();
        form.setRegion(text(result, "region"));
        form.setStartDate(text(result, "startDate"));
        form.setEndDate(text(result, "endDate"));
        form.setDurationDays(integer(result, "durationDays"));
        form.setSeason(text(result, "season"));
        form.setCompanion(text(result, "companion"));
        form.setTransportation(text(result, "transportation"));
        form.setPartySize(integer(result, "partySize"));
        form.setAccommodationNeeded(bool(result, "accommodationNeeded"));
        // "2박 3일"처럼 박 수를 적었다면 숙박이 필요한 여행이다. AI가 비워 둔 경우에만 서버가 채운다.
        if (form.getAccommodationNeeded().isEmpty() && draft.getOriginalText().matches("(?s).*[1-9][0-9]?\\s*박.*"))
            form.setAccommodationNeeded("true");
        form.setIncludeNearby(bool(result, "includeNearby"));
        String purpose = purposeText(result, "travelPurpose", 200);
        String concept = purposeText(result, "suggestedConcept", 100);
        // 사용자가 하고 싶은 것을 말하지 않았을 때만 AI가 제안한 여행 방향을 목적 자리에 넣고, 제안이라는 표시를 남긴다.
        boolean suggested = purpose.isEmpty() && !concept.isEmpty();
        form.setTravelPurpose(suggested ? concept : purpose);
        form.setPurposeSuggested(suggested);
        // "부산 말고"처럼 가지 않겠다고 한 지역. 한글 지역 이름만 받고, 가고 싶다고 한 지역과 같으면 모순이라 버린다.
        JsonNode excluded = result.get("excludedRegions");
        if (!excluded.isArray() || excluded.size() > 5) throw invalid();
        List<String> excludedRegions = new ArrayList<>();
        for (JsonNode name : excluded) {
            if (!name.isTextual()) throw invalid();
            String value = name.asText().replaceAll("\\s+", "");
            if (!value.matches("[가-힣]{2,20}")) throw invalid();
            if (!value.equals(form.getRegion().replaceAll("\\s+", "")) && !excludedRegions.contains(value)) excludedRegions.add(value);
        }
        form.setExcludedRegions(excludedRegions);
        JsonNode budget = result.get("budget");
        if (!budget.isNull() && !budget.isNumber()) throw invalid();
        form.setBudget(budget.isNull() ? "" : budget.decimalValue().toPlainString());
        JsonNode themes = result.get("themes");
        if (!themes.isArray() || themes.size() > 100) throw invalid();
        List<String> themeValues = new ArrayList<>();
        for (JsonNode theme : themes) {
            if (!theme.isTextual()) throw invalid();
            themeValues.add(theme.asText());
        }
        form.setThemes(themeValues);
        // 계절을 말하지 않았으면 여행 시작일(없으면 오늘)의 계절로 채운다. 날짜 계산이라 AI에 맡기지 않는다.
        if (form.getSeason().isEmpty()) {
            LocalDate when = form.getStartDate().matches("\\d{4}-\\d{2}-\\d{2}") ? LocalDate.parse(form.getStartDate())
                    : LocalDate.now(ZoneId.of("Asia/Seoul"));
            String season = seasonName(when.getMonthValue());
            choices.getOrDefault("season", List.of()).stream().filter(k -> k.name().equals(season)).findFirst()
                    .ifPresent(k -> form.setSeason(k.getSelectionValue()));
        }
        if (!result.get("summary").isTextual()) throw invalid();
        String summary = result.get("summary").asText().strip();
        if (summary.length() > 1000) throw invalid();
        var errors = new BeanPropertyBindingResult(form, "reviewForm");
        validate(form, errors, choices);
        if (errors.hasErrors()) {
            // 원문이나 응답값을 남기지 않고 검증에 실패한 필드 이름만 기록한다.
            log.warn("여행 조건 분석 검증 실패 필드: {}", errors.getFieldErrors().stream()
                    .map(org.springframework.validation.FieldError::getField).distinct().toList());
            throw invalid();
        }
        // 조건 보완 화면은 마지막 날을 묻지 않고 출발일과 일수만 보여 준다. AI가 날짜만 찾았다면 일수를 날짜로 채워 둔다(검증을 통과한 날짜라 바로 계산한다).
        if (form.getDurationDays().isEmpty() && !form.getStartDate().isEmpty() && !form.getEndDate().isEmpty()) {
            form.setDurationDays(String.valueOf(ChronoUnit.DAYS.between(
                    LocalDate.parse(form.getStartDate()), LocalDate.parse(form.getEndDate())) + 1));
        }
        return new Analysis(form, summary);
    }

    /** 월을 계절 키워드 이름으로 바꾼다. 3~5월 봄, 6~8월 여름, 9~11월 가을, 12~2월 겨울. */
    public static String seasonName(int month) {
        if (month >= 3 && month <= 5) return "봄";
        if (month >= 6 && month <= 8) return "여름";
        if (month >= 9 && month <= 11) return "가을";
        return "겨울";
    }

    // 목적·제안 문장에는 태그·URL·이메일 형태·긴 숫자열을 허용하지 않는다.
    private String purposeText(JsonNode result, String field, int maxLength) {
        if (!result.get(field).isTextual()) throw invalid();
        String value = result.get(field).asText().strip();
        if (value.length() > maxLength || value.contains("<") || value.contains(">")
                || value.matches("(?s).*(https?://|@|[0-9]{3,}).*")) throw invalid();
        return value;
    }

    public ConditionReviewForm fromKeywords(TravelConditionForm draft) {
        ConditionReviewForm form = new ConditionReviewForm();
        form.setRegion(draft.getKeywordNames("REGION"));
        form.setStartDate(draft.getStartDate());
        form.setEndDate(draft.getEndDate());
        form.setDurationDays(draft.getDurationDays());
        form.setBudget(draft.getBudget());
        form.setSeason(draft.getSeason());
        form.setCompanion(draft.getCompanion());
        form.setTransportation(draft.getTransportation());
        form.setThemes(List.copyOf(draft.getThemes()));
        return form;
    }

    public ConditionReviewForm fromConfirmed(ConfirmedTravelConditions c) {
        var form = new ConditionReviewForm();
        form.setTravelPurpose(c.travelPurpose() == null ? "" : c.travelPurpose());
        form.setPurposeSuggested(c.purposeSuggested());
        form.setExcludedRegions(c.excludedRegions());
        form.setRegion(c.region());
        form.setStartDate(c.startDate() == null ? "" : c.startDate().toString());
        form.setEndDate(c.endDate() == null ? "" : c.endDate().toString());
        form.setDurationDays(c.durationDays() == null ? "" : c.durationDays().toString());
        form.setBudget(c.budget() == null ? "" : c.budget().toPlainString());
        form.setPartySize(c.partySize() == null ? "" : c.partySize().toString());
        form.setAccommodationNeeded(c.accommodationNeeded() == null ? "" : c.accommodationNeeded().toString());
        form.setIncludeNearby(c.includeNearby() == null ? "" : c.includeNearby().toString());
        form.setThemes(c.selectedKeywords().stream().filter(k -> "THEME".equals(k.categoryCode())).map(TravelKeyword::getSelectionValue).toList());
        for(var keyword : c.selectedKeywords()) switch(keyword.categoryCode()) {
            case "SEASON" -> form.setSeason(keyword.getSelectionValue());
            case "COMPANION" -> form.setCompanion(keyword.getSelectionValue());
            case "TRANSPORTATION" -> form.setTransportation(keyword.getSelectionValue());
            default -> { }
        }
        return form;
    }

    public void validate(ConditionReviewForm form, Errors errors, Map<String, List<TravelKeyword>> choices) {
        validator.validate(form, errors, choices, true);
        if (!form.getPartySize().isEmpty()) {
            try {
                int size = Integer.parseInt(form.getPartySize());
                if (size < 1 || size > 32767) throw new NumberFormatException();
            } catch (NumberFormatException ex) {
                errors.rejectValue("partySize", "invalid", "전체 인원은 1~32,767 사이의 정수로 입력해 주세요.");
            }
        }
        if (!List.of("", "true", "false").contains(form.getAccommodationNeeded()))
            errors.rejectValue("accommodationNeeded", "invalid", "숙박 여부를 화면의 항목에서 선택해 주세요.");
        if (!List.of("", "true", "false").contains(form.getIncludeNearby()))
            errors.rejectValue("includeNearby", "invalid", "주변 지역 포함 여부를 화면의 항목에서 선택해 주세요.");
    }

    public ConfirmedTravelConditions confirm(ConditionReviewForm form, TravelConditionForm source,
            String summary, Map<String, List<TravelKeyword>> choices) {
        LocalDate start = date(form.getStartDate());
        LocalDate end = date(form.getEndDate());
        Integer days = number(form.getDurationDays());
        if (start != null) days = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        Map<String, List<String>> selected = Map.of("season", List.of(form.getSeason()),
                "companion", List.of(form.getCompanion()), "transportation", List.of(form.getTransportation()),
                "themes", form.getThemes().stream().distinct().toList());
        List<TravelKeyword> snapshots = new ArrayList<>();
        choices.get("region").stream().filter(k -> k.name().equals(form.getRegion())).findFirst().ifPresent(snapshots::add);
        selected.forEach((field, values) -> values.forEach(value -> choices.get(field).stream()
                .filter(k -> k.getSelectionValue().equals(value)).findFirst().ifPresent(snapshots::add)));
        return new ConfirmedTravelConditions(source.getInputMode(), source.getOriginalText(), form.getRegion(),
                start, end, days, form.getBudget().isEmpty() ? null : new BigDecimal(form.getBudget()),
                number(form.getPartySize()), flag(form.getAccommodationNeeded()), flag(form.getIncludeNearby()),
                List.copyOf(snapshots), summary).withPurpose(form.getTravelPurpose(), form.isPurposeSuggested())
                .withExcludedRegions(form.getExcludedRegions());
    }

    private LocalDate date(String value) { return value.isEmpty() ? null : LocalDate.parse(value); }
    private Integer number(String value) { return value.isEmpty() ? null : Integer.valueOf(value); }
    private Boolean flag(String value) { return value.isEmpty() ? null : Boolean.valueOf(value); }
    private String text(JsonNode result, String field) {
        JsonNode value = result.get(field);
        if (!value.isNull() && !value.isTextual()) throw invalid();
        return value.isNull() ? "" : value.asText().strip();
    }
    private String integer(JsonNode result, String field) {
        JsonNode value = result.get(field);
        if (!value.isNull() && (!value.isIntegralNumber() || !value.canConvertToInt())) throw invalid();
        return value.isNull() ? "" : Integer.toString(value.intValue());
    }
    private String bool(JsonNode result, String field) {
        JsonNode value = result.get(field);
        if (!value.isNull() && !value.isBoolean()) throw invalid();
        return value.isNull() ? "" : Boolean.toString(value.booleanValue());
    }
    private ConditionAnalysisException invalid() {
        return new ConditionAnalysisException("분석 결과의 조건이 맞지 않아 반영하지 않았어요. 문장을 구체적으로 적고 다시 시도해 주세요.");
    }
}
