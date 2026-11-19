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
            "accommodationNeeded", "includeNearby", "summary", "travelPurpose", "suggestedConcept", "excludedRegions", "moods", "pace", "followUpQuestions");

    /** notices는 해석하지 못해 비운 칸의 안내다(예: "여행 목적은 해석하지 못해 비워 두었어요."). 없으면 비어 있다. */
    public record Analysis(ConditionReviewForm form, String summary, List<String> notices, List<FollowUp> followUps) {
        public Analysis(ConditionReviewForm form, String summary) { this(form, summary, List.of(), List.of()); }
    }

    /**
     * AI가 추천 전에 더 물어보면 좋겠다고 판단한 질문. field가 조건 칸 이름이면 그 칸에서 답하고,
     * EXTRA_FIELD면 조건 칸에 없는 정보라 답을 받아 여행 목적 뒤에 "label: 답"으로 붙인다.
     */
    public record FollowUp(String field, String question, String label) implements java.io.Serializable {
        public boolean extra() { return EXTRA_FIELD.equals(field); }
    }
    public static final String EXTRA_FIELD = "extra";
    /** 되물을 수 있는 칸. 계절은 날짜로 서버가 정하고 목적은 extra 질문으로 받으므로 넣지 않는다. */
    private static final Set<String> FOLLOW_UP_FIELDS = Set.of("region", "durationDays", "startDate", "companion", "themes",
            "moods", "pace", "partySize", "budget", "transportation", "accommodationNeeded", "includeNearby", EXTRA_FIELD);

    /** 칸 이름과 안내에 붙일 말. 해석하지 못한 칸을 비울 때 "○○은 해석하지 못해 비워 두었어요"로 알린다. */
    private static final Map<String, String> FIELD_LABELS = Map.ofEntries(
            Map.entry("region", "여행 지역은"), Map.entry("startDate", "여행 날짜는"), Map.entry("endDate", "여행 날짜는"),
            Map.entry("durationDays", "여행 일수는"), Map.entry("season", "계절은"), Map.entry("companion", "동행자는"),
            Map.entry("themes", "여행 취향은"), Map.entry("budget", "예산은"), Map.entry("transportation", "이동수단은"),
            Map.entry("partySize", "전체 인원은"), Map.entry("accommodationNeeded", "숙박 여부는"),
            Map.entry("includeNearby", "주변 지역 포함 여부는"), Map.entry("travelPurpose", "여행 목적은"),
            Map.entry("moods", "여행 분위기는"), Map.entry("pace", "여행 속도는"));

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
        // 응답 형식(자료형·항목)이 틀리면 전체를 반영하지 않지만, 값만 맞지 않는 칸은 그 칸만 비우고 안내한 뒤 진행한다.
        List<String> notices = new ArrayList<>();
        String purpose = purposeText(result, "travelPurpose");
        String concept = purposeText(result, "suggestedConcept");
        boolean purposeSaid = !purpose.isEmpty();
        if (purposeSaid && !validPurpose(purpose, 200)) {
            purpose = "";
            notices.add(FIELD_LABELS.get("travelPurpose") + " 해석하지 못해 비워 두었어요.");
        }
        // 제안 방향은 사용자가 말한 내용이 아니므로 맞지 않으면 알리지 않고 쓰지 않는다.
        if (!validPurpose(concept, 100)) concept = "";
        // 사용자가 하고 싶은 것을 말하지 않았을 때만 AI가 제안한 여행 방향을 목적 자리에 넣고, 제안이라는 표시를 남긴다.
        boolean suggested = !purposeSaid && !concept.isEmpty();
        form.setTravelPurpose(suggested ? concept : purpose);
        form.setPurposeSuggested(suggested);
        // "부산 말고"처럼 가지 않겠다고 한 지역. 한글 지역 이름만 받고, 가고 싶다고 한 지역과 같으면 모순이라 버린다.
        JsonNode excluded = result.get("excludedRegions");
        if (!excluded.isArray()) throw invalid();
        List<String> excludedRegions = new ArrayList<>();
        boolean excludedValid = excluded.size() <= 5;
        for (JsonNode name : excluded) {
            if (!name.isTextual()) throw invalid();
            String value = name.asText().replaceAll("\\s+", "");
            if (!value.matches("[가-힣]{2,20}")) excludedValid = false;
            else if (!value.equals(form.getRegion().replaceAll("\\s+", "")) && !excludedRegions.contains(value)) excludedRegions.add(value);
        }
        if (!excludedValid) {
            excludedRegions.clear();
            notices.add("추천에서 뺄 지역은 해석하지 못해 비워 두었어요.");
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
        // 문장에서 읽은 분위기·속도. 다시 묻지 않고 확인 화면에 미리 골라 두어, 다르면 사용자가 바꾼다(2026-11-16).
        JsonNode moods = result.get("moods");
        if (!moods.isArray() || moods.size() > 100) throw invalid();
        List<String> moodValues = new ArrayList<>();
        for (JsonNode mood : moods) {
            if (!mood.isTextual()) throw invalid();
            moodValues.add(mood.asText());
        }
        form.setMoods(moodValues);
        form.setPace(text(result, "pace"));
        if (!result.get("summary").isTextual()) throw invalid();
        String summary = result.get("summary").asText().strip();
        if (summary.length() > 1000) throw invalid();
        // 지원하지 않는 값(8일 이상, 지난 출발일, 목록에 없는 선택지 등)은 그 칸만 비우고 안내한다.
        clearInvalidFields(form, choices, notices);
        // 계절을 말하지 않았으면 여행 시작일(없으면 오늘)의 계절로 채운다. 날짜 계산이라 AI에 맡기지 않는다.
        fillSeasonFromStartDate(form, choices, true);
        var errors = new BeanPropertyBindingResult(form, "reviewForm");
        validate(form, errors, choices);
        if (errors.hasErrors()) {
            // 칸을 비운 뒤에도 맞지 않으면 반영하지 않는다. 원문이나 응답값을 남기지 않고 필드 이름만 기록한다.
            log.warn("여행 조건 분석 검증 실패 필드: {}", errors.getFieldErrors().stream()
                    .map(org.springframework.validation.FieldError::getField).distinct().toList());
            throw invalid();
        }
        // 조건 보완 화면은 마지막 날을 묻지 않고 출발일과 일수만 보여 준다. AI가 날짜만 찾았다면 일수를 날짜로 채워 둔다(검증을 통과한 날짜라 바로 계산한다).
        if (form.getDurationDays().isEmpty() && !form.getStartDate().isEmpty() && !form.getEndDate().isEmpty()) {
            form.setDurationDays(String.valueOf(ChronoUnit.DAYS.between(
                    LocalDate.parse(form.getStartDate()), LocalDate.parse(form.getEndDate())) + 1));
        }
        return new Analysis(form, summary, List.copyOf(notices), followUps(result.get("followUpQuestions")));
    }

    /**
     * AI의 보완 질문을 검사한다. 사용자가 한 말이 아니라 제안이므로, 맞지 않는 질문은 알리지 않고 그 질문만 버린다.
     * 칸 이름은 허용 목록 안에서만 받고, 같은 칸을 두 번 묻거나 extra 질문이 둘 이상이면 앞의 것만 쓴다.
     */
    private List<FollowUp> followUps(JsonNode questions) {
        if (questions == null || !questions.isArray()) throw invalid();
        List<FollowUp> kept = new ArrayList<>();
        for (JsonNode item : questions) {
            if (!item.isObject() || !item.path("field").isTextual() || !item.path("question").isTextual()
                    || !item.path("label").isTextual()) throw invalid();
            String field = item.get("field").asText();
            String question = item.get("question").asText().strip();
            String label = item.get("label").asText().strip();
            if (kept.size() == 3 || !FOLLOW_UP_FIELDS.contains(field) || !validPurpose(question, 60) || question.length() < 5) continue;
            if (kept.stream().anyMatch(f -> f.field().equals(field))) continue;
            // extra 답은 "label: 답"으로 목적에 붙으므로 이름표가 있어야 한다.
            if (EXTRA_FIELD.equals(field) && (!validPurpose(label, 15) || label.contains(":"))) continue;
            kept.add(new FollowUp(field, question, EXTRA_FIELD.equals(field) ? label : ""));
        }
        return List.copyOf(kept);
    }

    /**
     * 보완 질문(extra)의 답을 여행 목적 뒤에 "label: 답"으로 붙이고 답 칸을 비운다. 목적이 AI 제안이었으면
     * 사용자가 직접 답한 내용이 더 정확하므로 제안 대신 답만 남긴다. 답의 길이·형식은 validate에서 먼저 검사한다.
     */
    public void applyFollowUpAnswer(ConditionReviewForm form, FollowUp extra) {
        String answer = form.getFollowUpAnswer() == null ? "" : form.getFollowUpAnswer().strip();
        form.setFollowUpAnswer("");
        if (extra == null || answer.isEmpty()) return;
        String added = extra.label() + ": " + answer;
        String purpose = form.getTravelPurpose() == null ? "" : form.getTravelPurpose().strip();
        form.setTravelPurpose(form.isPurposeSuggested() || purpose.isEmpty() ? added : purpose + ", " + added);
        form.setPurposeSuggested(false);
    }

    /**
     * 검증에 실패한 칸을 비우고 안내를 notices에 더한다. 칸을 비우면 다른 칸이 새로 맞지 않을 수 있어
     * (예: 일수를 비우면 출발일만 남음) 오류가 없어질 때까지 몇 번 되풀이한다.
     */
    private void clearInvalidFields(ConditionReviewForm form, Map<String, List<TravelKeyword>> choices, List<String> notices) {
        for (int pass = 0; pass < 3; pass++) {
            var errors = new BeanPropertyBindingResult(form, "reviewForm");
            validate(form, errors, choices);
            if (!errors.hasErrors()) return;
            // 원문이나 응답값을 남기지 않고 비운 칸 이름만 기록한다.
            log.info("여행 조건 분석에서 비운 칸: {}", errors.getFieldErrors().stream()
                    .map(org.springframework.validation.FieldError::getField).distinct().toList());
            for (var error : errors.getFieldErrors()) {
                String field = error.getField();
                // 같은 칸에 오류가 여러 개면 한 번만 비우고 한 번만 알린다. 출발일·마지막 날은 함께 비운다.
                boolean dates = field.equals("startDate") || field.equals("endDate");
                if (dates ? form.getStartDate().isEmpty() && form.getEndDate().isEmpty() : isEmpty(form, field)) continue;
                notices.add(notice(field, error.getCode()));
                clear(form, field);
            }
        }
    }

    private static String notice(String field, String code) {
        if ("tooLong".equals(code) && field.equals("durationDays")) return "여행 일수는 7일까지만 정할 수 있어 비워 두었어요. 일정은 최대 7일까지 만들어요.";
        if ("tooLong".equals(code)) return "여행 기간이 7일을 넘어 여행 날짜를 비워 두었어요. 일정은 최대 7일까지 만들어요.";
        if ("past".equals(code)) return "출발일이 이미 지난 날짜라 여행 날짜를 비워 두었어요.";
        if ("mismatch".equals(code) && field.equals("durationDays")) return "여행 일수가 날짜와 달라 날짜를 기준으로 다시 계산했어요.";
        if ("mismatch".equals(code) && field.equals("season")) return "계절이 출발일과 맞지 않아 출발일을 기준으로 다시 정했어요.";
        // 비운 계절은 바로 뒤에서 출발일(없으면 오늘)의 계절로 다시 채운다.
        if (field.equals("season")) return "계절은 해석하지 못해 출발일(없으면 오늘) 기준으로 다시 정했어요.";
        return FIELD_LABELS.getOrDefault(field, "일부 조건은") + " 해석하지 못해 비워 두었어요.";
    }

    private static boolean isEmpty(ConditionReviewForm form, String field) {
        return switch (field) {
            case "themes" -> form.getThemes().isEmpty();
            case "region" -> form.getRegion().isEmpty();
            case "durationDays" -> form.getDurationDays().isEmpty();
            case "season" -> form.getSeason().isEmpty();
            case "companion" -> form.getCompanion().isEmpty();
            case "transportation" -> form.getTransportation().isEmpty();
            case "budget" -> form.getBudget().isEmpty();
            case "partySize" -> form.getPartySize().isEmpty();
            case "accommodationNeeded" -> form.getAccommodationNeeded().isEmpty();
            case "includeNearby" -> form.getIncludeNearby().isEmpty();
            case "travelPurpose" -> form.getTravelPurpose().isEmpty();
            case "moods" -> form.getMoods().isEmpty();
            case "pace" -> form.getPace().isEmpty();
            default -> false;
        };
    }

    private static void clear(ConditionReviewForm form, String field) {
        switch (field) {
            case "startDate", "endDate" -> { form.setStartDate(""); form.setEndDate(""); }
            case "themes" -> form.setThemes(List.of());
            case "region" -> form.setRegion("");
            case "durationDays" -> form.setDurationDays("");
            case "season" -> form.setSeason("");
            case "companion" -> form.setCompanion("");
            case "transportation" -> form.setTransportation("");
            case "budget" -> form.setBudget("");
            case "partySize" -> form.setPartySize("");
            case "accommodationNeeded" -> form.setAccommodationNeeded("");
            case "includeNearby" -> form.setIncludeNearby("");
            case "travelPurpose" -> { form.setTravelPurpose(""); form.setPurposeSuggested(false); }
            case "moods" -> form.setMoods(List.of());
            case "pace" -> form.setPace("");
            default -> { }
        }
    }

    /** 월을 계절 키워드 이름으로 바꾼다. 3~5월 봄, 6~8월 여름, 9~11월 가을, 12~2월 겨울. */
    public static String seasonName(int month) {
        if (month >= 3 && month <= 5) return "봄";
        if (month >= 6 && month <= 8) return "여름";
        if (month >= 9 && month <= 11) return "가을";
        return "겨울";
    }

    // 목적·제안 문장은 문자열이어야 한다(응답 형식). 내용 검사는 validPurpose가 칸별로 한다.
    private String purposeText(JsonNode result, String field) {
        if (!result.get(field).isTextual()) throw invalid();
        return result.get(field).asText().strip();
    }

    /**
     * 여행 목적 문장으로 받을 수 있는지. 태그·URL·이메일 형태와 연락처·계좌번호 같은 긴 숫자(6자리 이상)는 받지 않는다.
     * "100대 명산", "2026 축제"처럼 짧은 숫자는 목적에 흔히 들어가므로 허용한다. 조건 수정 화면에서 고친 목적도 같은 기준이다.
     */
    public static boolean validPurpose(String value, int maxLength) {
        return value.length() <= maxLength && !value.contains("<") && !value.contains(">")
                && !value.matches("(?s).*(https?://|@|[0-9]{6,}).*");
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
        form.setMoods(List.copyOf(draft.getMoods()));
        form.setPace(draft.getPace());
        return form;
    }

    /**
     * 조건 고르기(키워드) 입력으로 추천을 시작할 때 쓴다. 계절을 고르지 않고 출발일만 적었으면 출발일의 계절을 채운다.
     * 출발일이 없으면 '상관없음'을 그대로 둔다(화면에서 고른 선택을 바꾸지 않는다). 화면의 자동 선택과 같은 기준이다.
     */
    public ConditionReviewForm fromKeywords(TravelConditionForm draft, Map<String, List<TravelKeyword>> choices) {
        ConditionReviewForm form = fromKeywords(draft);
        fillSeasonFromStartDate(form, choices, false);
        return form;
    }

    /** 계절이 비어 있으면 출발일의 계절로 채운다. fallbackToday가 true면 출발일이 없을 때 오늘 기준으로 채운다. */
    private static void fillSeasonFromStartDate(ConditionReviewForm form, Map<String, List<TravelKeyword>> choices, boolean fallbackToday) {
        if (!form.getSeason().isEmpty()) return;
        boolean hasStart = form.getStartDate().matches("\\d{4}-\\d{2}-\\d{2}");
        if (!hasStart && !fallbackToday) return;
        LocalDate when = hasStart ? LocalDate.parse(form.getStartDate()) : LocalDate.now(ZoneId.of("Asia/Seoul"));
        String season = seasonName(when.getMonthValue());
        choices.getOrDefault("season", List.of()).stream().filter(k -> k.name().equals(season)).findFirst()
                .ifPresent(k -> form.setSeason(k.getSelectionValue()));
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
        form.setMoods(c.selectedKeywords().stream().filter(k -> "MOOD".equals(k.categoryCode())).map(TravelKeyword::getSelectionValue).toList());
        for(var keyword : c.selectedKeywords()) switch(keyword.categoryCode()) {
            case "SEASON" -> form.setSeason(keyword.getSelectionValue());
            case "COMPANION" -> form.setCompanion(keyword.getSelectionValue());
            case "TRANSPORTATION" -> form.setTransportation(keyword.getSelectionValue());
            case "PACE" -> form.setPace(keyword.getSelectionValue());
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
        if (!validPurpose(form.getTravelPurpose(), 200))
            errors.rejectValue("travelPurpose", "invalid", "여행 목적은 200자 이내로 적고, 주소·이메일·긴 숫자는 빼 주세요.");
        // AI 보완 질문의 답은 여행 목적 뒤에 붙으므로 목적과 같은 기준으로 검사하고, 합친 길이도 200자를 넘지 않게 한다.
        String answer = form.getFollowUpAnswer() == null ? "" : form.getFollowUpAnswer().strip();
        if (!answer.isEmpty() && !validPurpose(answer, 100))
            errors.rejectValue("followUpAnswer", "invalid", "질문의 답은 100자 이내로 적고, 주소·이메일·긴 숫자는 빼 주세요.");
        else if (!answer.isEmpty() && form.getTravelPurpose().strip().length() + answer.length() + 20 > 200)
            errors.rejectValue("followUpAnswer", "tooLong", "여행 목적과 질문의 답을 합쳐 180자 안으로 줄여 주세요.");
    }

    public ConfirmedTravelConditions confirm(ConditionReviewForm form, TravelConditionForm source,
            String summary, Map<String, List<TravelKeyword>> choices) {
        LocalDate start = date(form.getStartDate());
        LocalDate end = date(form.getEndDate());
        Integer days = number(form.getDurationDays());
        if (start != null) days = Math.toIntExact(ChronoUnit.DAYS.between(start, end) + 1);
        Map<String, List<String>> selected = Map.of("season", List.of(form.getSeason()),
                "companion", List.of(form.getCompanion()), "transportation", List.of(form.getTransportation()),
                "themes", form.getThemes().stream().distinct().toList(),
                "moods", form.getMoods().stream().distinct().toList(), "pace", List.of(form.getPace()));
        List<TravelKeyword> snapshots = new ArrayList<>();
        choices.get("region").stream().filter(k -> k.name().equals(form.getRegion())).findFirst().ifPresent(snapshots::add);
        selected.forEach((field, values) -> values.forEach(value -> choices.getOrDefault(field, List.of()).stream()
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
