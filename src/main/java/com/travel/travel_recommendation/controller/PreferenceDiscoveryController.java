package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.service.PreferenceDiscovery;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import tools.jackson.databind.ObjectMapper;

/**
 * 선택형 여행 취향 탐색 화면(SFR-02-05·06). 답변과 결과는 세션에만 두고, 사용자가 확인한 취향만
 * 기존 조건 입력의 '여행 취향'으로 옮긴다. 이후 흐름(조건 확인·보완·확정·추천)은 기존과 같다.
 */
@Controller
@RequiredArgsConstructor
@RequestMapping("/travel/conditions/discover")
public class PreferenceDiscoveryController {
    public static final String ANSWERS_KEY = "preferenceDiscoveryAnswers";
    /** 조건 확인 화면에서 '취향 탐색으로 고른 취향'을 설명하는 데 쓴다. */
    public static final String APPLIED_KEY = "preferenceDiscoveryApplied";
    /** 이번 탐색의 첫 질문 ID. 무작위로 한 번 고른 뒤 다시 탐색할 때까지 유지한다. */
    public static final String FIRST_KEY = "preferenceDiscoveryFirst";
    /** 바로 추천받기에 쓸, 무작위로 고른 토요일. */
    public static final String WEEKEND_KEY = "preferenceDiscoveryWeekend";
    private static final int PRESELECT = 3;
    private final TravelConditionOptions options;
    private final ObjectMapper mapper;

    /**
     * 질문을 한 개씩 보여 준다. step이 없으면 다음 질문을, 있으면 이미 답한 그 질문을 다시 보여 줘 답을 고치게 한다.
     * 앞 답에 따라 다음 질문이 달라지므로 한 화면에 모든 질문을 보여 주지 않는다.
     */
    @GetMapping
    String questions(@RequestParam(required = false) Integer step, HttpSession session, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var answers = answers(session);
        var ids = new ArrayList<>(answers.keySet());
        PreferenceDiscovery.Question question;
        int current;
        if (step != null && step >= 0 && step < ids.size()) {
            question = PreferenceDiscovery.question(ids.get(step));
            current = step;
        } else {
            question = expectedQuestion(answers, session);
            if (question == null) return "redirect:/travel/conditions/discover/result";
            current = answers.size();
        }
        model.addAttribute("question", question);
        model.addAttribute("step", current);
        model.addAttribute("total", PreferenceDiscovery.MAX_QUESTIONS);
        model.addAttribute("selected", answers.get(question.id()));
        model.addAttribute("history", PreferenceDiscovery.summarize(answers));
        return "travel/discover";
    }

    /**
     * 한 질문의 답을 저장한다. 앞 질문의 답을 바꾸면 이어지는 질문도 달라지므로 그 뒤 답은 지운다.
     * 같은 답을 다시 고르면 뒤 답은 그대로 둔다.
     */
    @PostMapping
    String answer(@RequestParam String question, @RequestParam String answer, @RequestParam int step, HttpSession session) {
        var answers = answers(session);
        if (step < 0 || step > answers.size()) throw forged();
        var kept = new LinkedHashMap<String, String>();
        answers.entrySet().stream().limit(step).forEach(e -> kept.put(e.getKey(), e.getValue()));
        // 이 순서에 나와야 하는 질문인지 서버에서 다시 정해 보고 비교한다.
        var expected = kept.isEmpty() ? PreferenceDiscovery.question(first(session)) : PreferenceDiscovery.nextQuestion(kept);
        if (expected == null || !expected.id().equals(question)) throw forged();
        var updated = answer.equals(answers.get(question)) ? answers : kept;
        updated.put(question, answer);
        try {
            PreferenceDiscovery.evaluate(updated);
        } catch (IllegalArgumentException invalid) {
            throw forged();
        }
        session.setAttribute(ANSWERS_KEY, updated);
        return PreferenceDiscovery.nextQuestion(updated) == null ? "redirect:/travel/conditions/discover/result" : "redirect:/travel/conditions/discover";
    }

    @GetMapping("/result")
    String result(HttpSession session, HttpServletResponse response, Model model) {
        response.setHeader("Cache-Control", "no-store");
        var answers = answers(session);
        if (answers.isEmpty()) return "redirect:/travel/conditions/discover";
        var result = PreferenceDiscovery.evaluate(answers);
        model.addAttribute("result", result);
        model.addAttribute("lastStep", answers.size() - 1);
        model.addAttribute("themes", themes());
        // 점수가 높은 순으로 최대 3개를 미리 골라 둔다. 사용자가 확인하고 바꿀 수 있다.
        model.addAttribute("preselected", result.preferences().stream().limit(PRESELECT)
            .map(p -> selectionValue(p.tag())).filter(Objects::nonNull).toList());
        // 바로 추천받기: 취향과 자동으로 고른 주말로 문장을 만들어 두고, 홈의 문장 입력과 같은 추천 요청으로 보낸다.
        // 스키·해수욕처럼 한 계절에만 하는 장면이 문장에 들어가면 그 계절 성수기의 주말로 잡는다.
        var season = PreferenceDiscovery.season(result);
        var saturday = weekend(session, season);
        model.addAttribute("tripSaturday", saturday);
        model.addAttribute("tripSeason", season == null ? null : season.getLabel());
        model.addAttribute("tripSentence", PreferenceDiscovery.tripSentence(result, saturday));
        model.addAttribute("inputToken", TravelConditionController.issueInputToken(session));
        return "travel/discover-result";
    }

    /**
     * 확인한 취향을 조건 입력의 여행 취향에 반영한다. 직접 고른 취향이 이미 있고 다르면
     * 바로 덮어쓰지 않고 어떻게 합칠지 먼저 묻는다(resolve: replace·merge·keep).
     */
    @PostMapping("/apply")
    String apply(@RequestParam(required = false) List<String> themes, @RequestParam(required = false) String resolve,
                 HttpSession session, HttpServletResponse response, Model model, RedirectAttributes redirect) {
        var answers = answers(session);
        if (answers.isEmpty()) return "redirect:/travel/conditions/discover";
        var allowed = themes().stream().map(TravelKeyword::getSelectionValue).toList();
        var chosen = themes == null ? List.<String>of() : themes.stream().distinct().toList();
        if (!allowed.containsAll(chosen))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "여행 취향은 화면에 있는 항목에서 선택해 주세요.");
        synchronized (session) {
            // 아직 제출하지 않은 미리 채운 값이 있으면 그것을, 없으면 검증된 초안을 기준으로 비교한다.
            var current = session.getAttribute(TravelConditionController.PREFILL_KEY) instanceof TravelConditionForm prefill ? prefill
                : session.getAttribute(TravelConditionController.DRAFT_KEY) instanceof TravelConditionForm saved ? saved : null;
            var draft = current != null && "KEYWORD".equals(current.getInputMode()) ? current : null;
            var existing = draft == null ? List.<String>of() : draft.getThemes();
            if (resolve == null && !existing.isEmpty() && !new HashSet<>(existing).equals(new HashSet<>(chosen))) {
                model.addAttribute("result", PreferenceDiscovery.evaluate(answers));
                model.addAttribute("lastStep", answers.size() - 1);
                model.addAttribute("themes", themes());
                model.addAttribute("preselected", chosen);
                model.addAttribute("existingNames", names(existing));
                model.addAttribute("chosenNames", names(chosen));
                return "travel/discover-result";
            }
            List<String> finalThemes = switch (resolve == null ? "replace" : resolve) {
                case "keep" -> existing;
                case "merge" -> { var merged = new ArrayList<>(existing); chosen.stream().filter(t -> !merged.contains(t)).forEach(merged::add); yield merged; }
                case "replace" -> chosen;
                default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "반영 방법을 다시 골라 주세요.");
            };
            // 탐색 결과는 입력 화면을 미리 채우는 값일 뿐이다. 조건 입력에서 다시 제출해 검증을 거쳐야 초안이 된다.
            var form = new TravelConditionForm();
            if (draft != null) {
                form.setRegion(draft.getRegion()); form.setStartDate(draft.getStartDate()); form.setEndDate(draft.getEndDate());
                form.setDurationDays(draft.getDurationDays()); form.setSeason(draft.getSeason()); form.setCompanion(draft.getCompanion());
                form.setBudget(draft.getBudget()); form.setTransportation(draft.getTransportation());
                // 분위기와 여행 속도도 취향 탐색과 상관없는 조건이므로 그대로 이어 준다.
                form.setMoods(List.copyOf(draft.getMoods())); form.setPace(draft.getPace());
            }
            form.setInputMode("KEYWORD");
            form.setThemes(List.copyOf(finalThemes));
            session.setAttribute(TravelConditionController.PREFILL_KEY, form);
            // '직접 고른 취향 유지'를 고르면 탐색 결과는 반영하지 않은 것으로 본다.
            session.setAttribute(APPLIED_KEY, "keep".equals(resolve) ? List.of() : names(chosen));
            // 조건이 바뀌었으므로 확인·확정 단계는 다시 거친다. 문장 입력 초안은 지우지 않는다.
            session.removeAttribute(TravelConditionReviewController.REVIEW_KEY);
            session.removeAttribute(TravelConditionReviewController.CONFIRMED_KEY);
        }
        redirect.addFlashAttribute("discoveryMessage", "keep".equals(resolve) ? "직접 고른 여행 취향을 그대로 유지했어요."
            : chosen.isEmpty() ? "고른 취향 없이 기존 조건 입력으로 이어서 진행해요."
            : "탐색한 취향을 '여행 취향'에 반영했어요. 나머지 조건을 확인하고 다음으로 넘어가 주세요.");
        return "redirect:/travel/conditions";
    }

    /** 탐색을 처음부터 다시 한다. 이전 답변과 점수는 남기지 않고, 첫 질문도 새로 뽑는다. */
    @PostMapping("/reset")
    String reset(HttpSession session) {
        session.removeAttribute(ANSWERS_KEY);
        session.removeAttribute(FIRST_KEY);
        session.removeAttribute(WEEKEND_KEY);
        return "redirect:/travel/conditions/discover";
    }

    @ExceptionHandler(ResponseStatusException.class)
    String error(ResponseStatusException error, HttpServletResponse response, Model model) {
        response.setStatus(error.getStatusCode().value());
        model.addAttribute("message", error.getReason());
        return "travel/discover-error";
    }

    private static ResponseStatusException forged() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "화면에 없는 질문이나 선택지예요. 취향 탐색을 처음부터 다시 진행해 주세요.");
    }

    /** 묻는 순서가 중요하므로 순서를 지키는 LinkedHashMap 복사본을 돌려준다. 예전 형식이거나 잘못된 답은 버린다. */
    @SuppressWarnings("unchecked")
    private static LinkedHashMap<String, String> answers(HttpSession session) {
        if (!(session.getAttribute(ANSWERS_KEY) instanceof LinkedHashMap<?, ?> saved)) return new LinkedHashMap<>();
        var answers = new LinkedHashMap<>((Map<String, String>) saved);
        try {
            PreferenceDiscovery.evaluate(answers);
            return answers;
        } catch (IllegalArgumentException | ClassCastException invalid) {
            session.removeAttribute(ANSWERS_KEY);
            return new LinkedHashMap<>();
        }
    }

    /** 첫 질문은 시작 질문 중 하나를 무작위로 골라 세션에 둔다. 새로고침해도 같은 질문이 나온다. */
    private static String first(HttpSession session) {
        if (session.getAttribute(FIRST_KEY) instanceof String id && PreferenceDiscovery.START_IDS.contains(id)) return id;
        var starts = PreferenceDiscovery.START_IDS;
        String id = starts.get(ThreadLocalRandom.current().nextInt(starts.size()));
        session.setAttribute(FIRST_KEY, id);
        return id;
    }

    /**
     * 바로 추천받기에 쓸 토요일. 한 번 고르면 새로고침해도 같은 날짜를 보여 준다.
     * 날짜가 지나 7일 범위를 벗어났거나(오래 열어 둔 세션) 답을 고쳐 계절이 바뀌었으면 새로 고른다.
     */
    private static LocalDate weekend(HttpSession session, PreferenceDiscovery.Season season) {
        var today = LocalDate.now(ZoneId.of("Asia/Seoul"));
        if (session.getAttribute(WEEKEND_KEY) instanceof LocalDate saved && PreferenceDiscovery.weekendFits(saved, today, season)) return saved;
        var saturday = PreferenceDiscovery.pickWeekend(today, season, ThreadLocalRandom.current());
        session.setAttribute(WEEKEND_KEY, saturday);
        return saturday;
    }

    /** 지금까지의 답으로 다음에 물어볼 질문. 다 물었으면 null이다. */
    private static PreferenceDiscovery.Question expectedQuestion(LinkedHashMap<String, String> answers, HttpSession session) {
        return answers.isEmpty() ? PreferenceDiscovery.question(first(session)) : PreferenceDiscovery.nextQuestion(answers);
    }
    private List<TravelKeyword> themes() {
        try {
            return options.getChoices().getOrDefault("themes", List.of());
        } catch (DataAccessException unavailable) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "여행 취향 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        }
    }
    // 태그는 키워드 값(value_json의 value)으로 찾고, DB가 없는 예시 목록은 이름으로 찾는다.
    private String selectionValue(String tag) {
        for (var keyword : themes()) {
            String value = keyword.valueJson() == null ? null : mapper.readTree(keyword.valueJson()).path("value").asText(null);
            if (tag.equals(value) || (value == null && PreferenceDiscovery.TAG_NAMES.get(tag).equals(keyword.name())))
                return keyword.getSelectionValue();
        }
        return null;
    }
    private List<String> names(List<String> selectionValues) {
        return themes().stream().filter(k -> selectionValues.contains(k.getSelectionValue())).map(TravelKeyword::name).toList();
    }
}
