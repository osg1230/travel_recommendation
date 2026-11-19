package com.travel.travel_recommendation.controller;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.security.Principal;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.propertyeditors.StringTrimmerEditor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BeanPropertyBindingResult;
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
    public static final String CONFIRMATION_TOKEN = "travelConfirmationToken";
    private static final String BUSY_KEY = "travelConditionAnalyzing";
    private final ConditionReviewService service;
    private final TravelConditionOptions options;
    private final TravelConditionValidator validator;
    private final TravelConditionService drafts;
    private final ObjectProvider<RecommendationJobService> jobs;
    private final AiRequestLimiter limiter;
    private static final String DIRECT_KEY = "travelDirectRecommendation";
    private static final String DIRECT_JOB = "travelDirectJob";
    /** 바로 추천 흐름에서 마지막으로 만든 추천 작업 ID. 오래된 조건 수정 화면을 보내면 이 추천으로 보낸다. */
    static final String LAST_JOB_KEY = "travelLastRecommendationJob";
    /** 문장 분석에서 해석하지 못해 비운 칸의 안내. 그 분석으로 만든 추천 화면에서 한 번 보여 준다. */
    static final String ANALYSIS_NOTICE_KEY = "travelAnalysisNotice";
    record AnalysisNotice(UUID jobId, List<String> messages) {}
    private record DirectJob(String token, String login, UUID id) {}

    /** notices는 문장 분석에서 비운 칸의 안내, followUps는 AI 보완 질문으로, 둘 다 조건 보완 화면에 보여 준다. */
    private record Review(String sourceId, ConditionReviewForm form, String summary, List<String> notices,
                          List<ConditionReviewService.FollowUp> followUps) {
        Review(String sourceId, ConditionReviewForm form, String summary) { this(sourceId, form, summary, List.of(), List.of()); }
        /** 조건 칸에 없는 정보를 물은 질문. 없으면 null이다. */
        ConditionReviewService.FollowUp extraQuestion() {
            return followUps.stream().filter(ConditionReviewService.FollowUp::extra).findFirst().orElse(null);
        }
    }

    @InitBinder("travelForm")
    void bindInput(WebDataBinder binder) {
        binder.setAllowedFields("inputMode", "originalText", "region", "startDate", "endDate",
            "durationDays", "season", "companion", "themes", "budget", "transportation", "moods", "pace");
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
    }

    /** 입력 한 번으로 분석과 추천을 연결한다. 누락된 선택 조건 때문에 보완 화면을 강제로 열지 않는다. */
    @PostMapping("/recommend")
    String start(@ModelAttribute("travelForm") TravelConditionForm form, BindingResult errors,
            @RequestParam String inputToken, HttpSession session, Principal principal, Model model,
            RedirectAttributes flash, HttpServletRequest request) {
        var choices = choices(model);
        model.addAttribute("preview", options.isPreview());
        model.addAttribute("choicesEmpty", choices.values().stream().anyMatch(List::isEmpty));
        model.addAttribute("inputToken", inputToken);
        validator.validate(form, errors, choices);
        if(Boolean.TRUE.equals(model.getAttribute("choicesUnavailable")))
            errors.reject("unavailable", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
        if(errors.hasErrors()) return "travel/conditions";
        String login = principal == null ? null : principal.getName();
        TravelConditionForm source;
        synchronized(session) {
            if(!inputToken.equals(session.getAttribute("travelInputToken"))) return staleStart(form, session, flash);
            if(session.getAttribute(DIRECT_JOB) instanceof DirectJob old && old.token().equals(inputToken)
                    && java.util.Objects.equals(old.login(), login)) {
                jobService().view(old.id(), login, RecommendationJobController.guestHash(session, false));
                return "redirect:/travel/recommendations/" + old.id();
            }
            if(session.getAttribute(BUSY_KEY) != null) {
                flash.addFlashAttribute("analysisError", "추천을 준비 중이에요. 잠시 기다려 주세요.");
                return "redirect:/travel/conditions";
            }
            // 유료 AI 호출 전에 요청 횟수를 확인한다. 넘었으면 적은 내용을 그대로 채워 둔 채 입력 화면으로 돌려보낸다.
            String limited = limiter.tryAcquire(login, request.getRemoteAddr());
            if(limited != null) {
                session.setAttribute(TravelConditionController.PREFILL_KEY, form);
                flash.addFlashAttribute("analysisError", limited);
                return "redirect:/travel/conditions";
            }
            source = drafts.createDraft(form, choices);
            session.setAttribute(TravelConditionController.DRAFT_KEY, source);
            session.removeAttribute(TravelConditionController.PREFILL_KEY);
            session.removeAttribute(REVIEW_KEY);
            session.removeAttribute(CONFIRMED_KEY);
            session.setAttribute(DIRECT_KEY, true);
            session.setAttribute(BUSY_KEY, source.getDraftId());
        }
        try {
            // 유료 분석을 기다리는 동안 다른 탭의 입력·로그아웃을 막지 않는다.
            var analysis = "TEXT".equals(source.getInputMode()) ? service.analyze(source, choices)
                : new ConditionReviewService.Analysis(service.fromKeywords(source, choices), "");
            synchronized(session) {
                if(draft(session) != source || !inputToken.equals(session.getAttribute("travelInputToken")))
                    return staleStart(form, session, flash);
                analysis.form().setRevision(UUID.randomUUID().toString());
                var conditions = service.confirm(analysis.form(), source, analysis.summary(), choices).forDirectRecommendation();
                // '속리산'처럼 여행지 목록에 없는 이름은 원하는 여행으로 옮겨 AI가 맞는 시·군을 찾게 하고, 추천 화면에서 알린다.
                var notices = new ArrayList<>(analysis.notices());
                String moved = regionToPurpose(analysis.form(), conditions);
                if(moved != null) {
                    notices.add(moved);
                    conditions = service.confirm(analysis.form(), source, analysis.summary(), choices).forDirectRecommendation();
                }
                session.setAttribute(REVIEW_KEY, new Review(source.getDraftId(), analysis.form(), analysis.summary(),
                        List.copyOf(notices), analysis.followUps()));
                // 지역에 맞는 여행지가 없으면 끝까지 처리한 뒤 실패하지 않도록 작업을 만들지 않고, 조건 수정 화면에서 지역을 고치게 한다.
                String regionProblem = regionProblem(conditions);
                if(regionProblem != null) {
                    flash.addFlashAttribute("reviewFieldErrors", Map.of("region", regionProblem));
                    return "redirect:/travel/conditions/review";
                }
                // AI가 추천 전에 물어볼 것이 있다고 판단했으면 추천을 만들지 않고 조건 보완 화면에서 질문을 보여 준다.
                // 바로 추천 흐름이라 그 화면에서 확정하면 곧바로 추천을 요청한다(confirm). 질문이 없으면 지금처럼 바로 추천한다.
                if(!analysis.followUps().isEmpty()) return "redirect:/travel/conditions/review";
                session.setAttribute(CONFIRMED_KEY, conditions);
                session.setAttribute(CONFIRMATION_TOKEN, UUID.randomUUID().toString());
                UUID id = jobService().create(conditions, login, RecommendationJobController.guestHash(session, true));
                session.setAttribute(DIRECT_JOB, new DirectJob(inputToken, login, id));
                rememberJob(session, id, List.copyOf(notices));
                return "redirect:/travel/recommendations/" + id;
            }
        } catch(ConditionAnalysisException ex) {
            flash.addFlashAttribute("analysisError", ex.getMessage());
            return "redirect:/travel/conditions";
        } catch(DataAccessException ex) {
            flash.addFlashAttribute("analysisError", "추천을 접수하지 못했어요. 잠시 후 다시 시도해 주세요.");
            return "redirect:/travel/conditions";
        } catch(IllegalStateException ex) {
            return "redirect:/travel/conditions";
        } finally {
            try { synchronized(session) {
                if(source.getDraftId().equals(session.getAttribute(BUSY_KEY))) session.removeAttribute(BUSY_KEY);
            } } catch(IllegalStateException ignored) { /* 만료된 세션은 복구하지 않는다. */ }
        }
    }

    /** 수정은 보고 있던 추천의 소유권을 확인한 뒤 그 조건으로 연다. 다른 탭의 조건을 섞지 않는다. */
    @GetMapping("/edit")
    String edit(@RequestParam UUID recommendationId, Principal principal, HttpSession session) {
        var c = jobService().conditions(recommendationId, principal == null ? null : principal.getName(),
            RecommendationJobController.guestHash(session, false));
        Map<String, List<TravelKeyword>> choices;
        try { choices = options.getChoices(); }
        catch(DataAccessException ex) { choices = Map.of(); } // 선택지를 못 읽어도 수정 화면은 열고, 그 화면에서 다시 안내한다.
        openReview(c, session, choices);
        return "redirect:/travel/conditions/review";
    }

    /**
     * 확정했던 조건으로 조건 수정 화면을 연다. 다른 탭의 조건을 섞지 않도록 이 조건으로 초안과 보완 내용을 새로 만든다.
     * 수정 화면에서 보낼 폼(확인 토큰 포함)을 돌려준다.
     */
    private ConditionReviewForm openReview(ConfirmedTravelConditions c, HttpSession session, Map<String, List<TravelKeyword>> choices) {
        TravelConditionForm source = service.fromConfirmed(c);
        source.setInputMode(c.inputMode()); source.setOriginalText(c.originalText());
        // 조건 고르기 입력이면 '처음 입력부터 수정하기'에서 지역이 다시 선택되도록 지역 이름을 선택지 값(키워드 ID)으로 바꿔 둔다.
        if("KEYWORD".equals(c.inputMode())) source.setRegion(choices.getOrDefault("region", List.of()).stream()
            .filter(k -> k.name().equals(c.region())).map(TravelKeyword::getSelectionValue).findFirst().orElse(""));
        var form = service.fromConfirmed(c);
        form.setRevision(UUID.randomUUID().toString());
        synchronized(session) {
            session.setAttribute(TravelConditionController.DRAFT_KEY, source);
            session.setAttribute(REVIEW_KEY, new Review(source.getDraftId(), form, c.interpretationSummary()));
            session.setAttribute(DIRECT_KEY, true);
            session.removeAttribute(DIRECT_JOB);
            // 남아 있던 미리 채운 값이 있으면 '처음 입력부터 수정하기'가 이 조건 대신 그 값을 보여 주므로 지운다.
            session.removeAttribute(TravelConditionController.PREFILL_KEY);
        }
        return form;
    }

    /**
     * 이미 확정했던 조건으로 다시 추천한다(추천 이력의 '같은 조건으로 다시 추천', SFR-14-03).
     * 지금 선택지 기준으로 다시 검증해, 지난 출발일·사용 중지된 선택지처럼 지금은 쓸 수 없는 값이 있으면 조건 수정 화면으로 보내 고치게 한다.
     * backUrl은 요청이 많아 막혔거나 접수하지 못했을 때 돌아갈 화면이다.
     */
    public String recommendAgain(ConfirmedTravelConditions previous, Principal principal, HttpServletRequest request,
                                 HttpSession session, RedirectAttributes flash, String backUrl) {
        Map<String, List<TravelKeyword>> choices;
        try { choices = options.getChoices(); }
        catch(DataAccessException ex) {
            flash.addFlashAttribute("historyError", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            return "redirect:" + backUrl;
        }
        String login = principal == null ? null : principal.getName();
        var form = openReview(previous, session, choices);
        var errors = new BeanPropertyBindingResult(form, "reviewForm");
        service.validate(form, errors, choices);
        synchronized(session) {
            if(errors.hasErrors()) {
                Map<String, String> fieldErrors = new LinkedHashMap<>();
                errors.getFieldErrors().forEach(e -> fieldErrors.putIfAbsent(e.getField(), e.getDefaultMessage()));
                flash.addFlashAttribute("reviewFieldErrors", fieldErrors);
                flash.addFlashAttribute("analysisError", "지금은 그대로 쓸 수 없는 조건이 있어요. 표시한 칸을 고친 뒤 추천받아 주세요.");
                return "redirect:/travel/conditions/review";
            }
            var conditions = service.confirm(form, draft(session), previous.interpretationSummary(), choices).forDirectRecommendation();
            String regionProblem = regionProblem(conditions);
            if(regionProblem != null) {
                flash.addFlashAttribute("reviewFieldErrors", Map.of("region", regionProblem));
                return "redirect:/travel/conditions/review";
            }
            String limited = limiter.tryAcquire(login, request.getRemoteAddr());
            if(limited != null) {
                flash.addFlashAttribute("historyError", limited);
                return "redirect:" + backUrl;
            }
            session.setAttribute(CONFIRMED_KEY, conditions);
            session.setAttribute(CONFIRMATION_TOKEN, UUID.randomUUID().toString());
            try {
                UUID id = jobService().create(conditions, login, RecommendationJobController.guestHash(session, true));
                rememberJob(session, id, List.of());
                return "redirect:/travel/recommendations/" + id;
            } catch(DataAccessException ex) {
                flash.addFlashAttribute("historyError", "추천을 접수하지 못했어요. 잠시 후 다시 시도해 주세요.");
                return "redirect:" + backUrl;
            }
        }
    }

    private RecommendationJobService jobService() {
        var result = jobs.getIfAvailable();
        if(result == null) throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "DB 연결 모드에서 여행지 추천을 이용해 주세요.");
        return result;
    }

    @InitBinder("reviewForm")
    void bind(WebDataBinder binder) {
        // 여행 목적과 제외 지역도 고칠 수 있다. 목적은 서버가 다시 검증하고, 제외 지역은 서버가 가진 목록 안에서만 뺄 수 있다(confirm).
        binder.setAllowedFields("revision", "region", "startDate", "endDate", "durationDays", "budget",
                "season", "companion", "themes", "moods", "pace", "transportation", "partySize", "accommodationNeeded", "includeNearby",
                "travelPurpose", "excludedRegions", "followUpAnswer");
        binder.registerCustomEditor(String.class, new StringTrimmerEditor(false));
    }

    @PostMapping("/analyze")
    String analyze(@RequestParam String draftId, HttpSession session, RedirectAttributes flash, Principal principal,
                   HttpServletRequest request) {
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
            // 유료 문장 분석 전에 요청 횟수를 확인한다.
            String limited = limiter.tryAcquire(principal == null ? null : principal.getName(), request.getRemoteAddr());
            if (limited != null) {
                flash.addFlashAttribute("analysisError", limited);
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
                session.setAttribute(REVIEW_KEY, new Review(draftId, analysis.form(), analysis.summary(), analysis.notices(), analysis.followUps()));
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
        model.addAttribute("directFlow",Boolean.TRUE.equals(session.getAttribute(DIRECT_KEY)));
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
            // 바로 추천·다시 추천에서 찾은 칸 오류(지역에 맞는 여행지 없음, 지난 출발일 등)를 그 칸 옆에 보여 준다.
            // 폼을 모델에 넣은 뒤에 넣어야 한다(폼을 다시 넣으면 Spring이 오류 정보를 지운다).
            if (model.getAttribute("reviewFieldErrors") instanceof Map<?, ?> fieldErrors) {
                var errors = new BeanPropertyBindingResult(review.form(), "reviewForm");
                fieldErrors.forEach((field, message) -> errors.rejectValue((String) field, "invalid", (String) message));
                model.addAttribute(BindingResult.MODEL_KEY_PREFIX + "reviewForm", errors);
            }
            context(model, draft, review);
        }
        choices(model);
        return "travel/condition-review";
    }

    @PostMapping("/confirm")
    String confirm(@ModelAttribute("reviewForm") ConditionReviewForm form, BindingResult errors,
                   HttpSession session, Model model, RedirectAttributes flash, Principal principal, HttpServletRequest request) {
        Map<String, List<TravelKeyword>> choices = choices(model);
        boolean direct = Boolean.TRUE.equals(session.getAttribute(DIRECT_KEY));
        model.addAttribute("directFlow", direct);
        synchronized (session) {
            TravelConditionForm draft = draft(session);
            Review review = (Review) session.getAttribute(REVIEW_KEY);
            if (draft == null || review == null || !review.sourceId().equals(draft.getDraftId())
                    || !review.form().getRevision().equals(form.getRevision())) return direct ? staleDirect(session, flash) : stale(flash);
            service.validate(form, errors, choices);
            if (Boolean.TRUE.equals(model.getAttribute("choicesUnavailable")))
                errors.reject("unavailable", "조건 목록을 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            if (errors.hasErrors()) {
                context(model, draft, review);
                return "travel/condition-review";
            }
            // 목적은 화면에서 고칠 수 있다. 그대로 두었으면 AI 제안 표시를 유지하고, 고쳤으면 사용자가 정한 목적으로 본다.
            form.setPurposeSuggested(form.getTravelPurpose().equals(review.form().getTravelPurpose()) && review.form().isPurposeSuggested());
            // AI 보완 질문(조건 칸에 없는 정보)의 답은 여행 목적 뒤에 붙인다. 질문이 없던 화면에서 보낸 답은 버린다.
            service.applyFollowUpAnswer(form, review.extraQuestion());
            // 제외 지역은 서버가 가진 목록 중 체크를 유지한 이름만 남기고, 가려고 고른 지역과 같은 이름은 뺀다.
            String region = form.getRegion().replaceAll("\\s+", "");
            List<String> kept = form.getExcludedRegions() == null ? List.of() : form.getExcludedRegions();
            form.setExcludedRegions(review.form().getExcludedRegions().stream().filter(kept::contains)
                .filter(name -> !name.replaceAll("\\s+", "").equals(region)).toList());
            var confirmed = service.confirm(form, draft, review.summary(), choices);
            // '속리산'처럼 여행지 목록에 없는 이름은 원하는 여행으로 옮겨 AI가 맞는 시·군을 찾게 한다.
            String moved = regionToPurpose(form, confirmed);
            if (moved != null) confirmed = service.confirm(form, draft, review.summary(), choices);
            // 지역에 맞는 여행지가 없으면 확정하지 않고 지역 칸에서 고치게 한다(목록에 없는 세부 지역 등).
            String regionProblem = regionProblem(confirmed);
            if (regionProblem != null) {
                errors.rejectValue("region", "noDestination", regionProblem);
                context(model, draft, review);
                return "travel/condition-review";
            }
            String login = principal == null ? null : principal.getName();
            if (direct) {
                // 바로 추천은 확정과 함께 유료 AI 추천을 요청하므로 요청 횟수를 먼저 확인한다.
                String limited = limiter.tryAcquire(login, request.getRemoteAddr());
                if (limited != null) {
                    errors.reject("limit", limited);
                    context(model, draft, review);
                    return "travel/condition-review";
                }
            }
            session.setAttribute(CONFIRMED_KEY, confirmed);
            session.setAttribute(CONFIRMATION_TOKEN, UUID.randomUUID().toString());
            // 확정 때마다 토큰을 교체해 이전 탭이나 중복 제출이 최신 결과를 덮어쓰지 않게 한다.
            form.setRevision(UUID.randomUUID().toString());
            Review updated = new Review(draft.getDraftId(), form, review.summary());
            session.setAttribute(REVIEW_KEY, updated);
            if(direct) {
                var conditions = confirmed.forDirectRecommendation();
                session.setAttribute(CONFIRMED_KEY, conditions);
                try {
                    UUID id = jobService().create(conditions, login, RecommendationJobController.guestHash(session, true));
                    rememberJob(session, id, moved == null ? List.of() : List.of(moved));
                    return "redirect:/travel/recommendations/" + id;
                } catch(DataAccessException ex) {
                    // 작업을 만들지 못했으면 고친 내용을 그대로 둔 채 다시 보낼 수 있게 한다. 화면은 새 확인 토큰으로 다시 그린다.
                    errors.reject("unavailable", "추천을 접수하지 못했어요. 잠시 후 다시 시도해 주세요.");
                    context(model, draft, updated);
                    return "travel/condition-review";
                }
            }
            if (moved != null) flash.addFlashAttribute("regionNotice", moved);
        }
        return "redirect:/travel/conditions/confirmed";
    }

    @GetMapping("/confirmed")
    String confirmed(HttpSession session, Model model) {
        Object confirmed = session.getAttribute(CONFIRMED_KEY);
        if (confirmed == null) return "redirect:/travel/conditions";
        model.addAttribute("conditions", confirmed);
        model.addAttribute("confirmationToken", session.getAttribute(CONFIRMATION_TOKEN));
        return "travel/condition-confirmed";
    }

    private TravelConditionForm draft(HttpSession session) {
        return (TravelConditionForm) session.getAttribute(TravelConditionController.DRAFT_KEY);
    }
    private void context(Model model, TravelConditionForm draft, Review review) {
        model.addAttribute("originalText", draft.getOriginalText());
        model.addAttribute("analysisSummary", review.summary());
        model.addAttribute("analyzed", "TEXT".equals(draft.getInputMode()));
        model.addAttribute("analysisNotices", review.notices());
        // 제외 지역 체크박스와 목적 안내는 서버가 가진 값으로 그린다. 오류로 다시 그릴 때도 체크를 푼 지역이 목록에서 사라지지 않는다.
        model.addAttribute("excludedChoices", review.form().getExcludedRegions());
        model.addAttribute("purposeSuggested", review.form().isPurposeSuggested());
        // AI 보완 질문. 확정하면 새 Review에는 넣지 않아, 다시 수정할 때는 같은 질문을 또 묻지 않는다.
        model.addAttribute("followUps", review.followUps());
    }

    /**
     * 지역 칸의 이름(예: 산 이름 '속리산', 아직 여행지가 아닌 시·군 '보은')으로도 그 첫 단어로도 여행지를 찾지 못하면,
     * 그 이름을 원하는 여행(목적) 앞에 붙이고 지역을 비운다(2026-10-31). 지역을 비우고 원하는 여행을 말한 추천은
     * AI가 맞는 시·군을 제안하고 관광공사 등록 장소로 확인해 여행지로 더하기 때문이다(RegionSuggestionService).
     * AI가 제안한 목적이었다면 사용자가 말한 이름으로 바꾼다. 옮겼으면 안내 문장을, 아니면 null을 돌려준다.
     */
    private String regionToPurpose(ConditionReviewForm form, ConfirmedTravelConditions conditions) {
        var jobService = jobs.getIfAvailable();
        if (jobService == null) return null;
        try { if (!jobService.regionUnknown(conditions)) return null; }
        catch (DataAccessException ex) { return null; }
        String place = form.getRegion().strip();
        String purpose = form.getTravelPurpose() == null ? "" : form.getTravelPurpose().strip();
        String joined = form.isPurposeSuggested() || purpose.isEmpty() ? place : place + " " + purpose;
        // 목적 칸은 200자까지라, 넘으면 이름만 둔다.
        form.setTravelPurpose(joined.length() <= 200 ? joined : place);
        form.setPurposeSuggested(false);
        form.setRegion("");
        return "'" + place + "'은(는) 아직 여행지 목록에 없어서 지역 대신 원하는 여행으로 옮겼어요. "
            + "AI가 맞는 시·군을 찾아 관광공사에 등록된 장소로 확인한 뒤 추천해요.";
    }

    /** 지역에 맞는 여행지가 없을 때의 안내. DB 없이 화면만 확인하는 모드이거나 확인하지 못하면 작업 단계에서 알린다. */
    private String regionProblem(ConfirmedTravelConditions conditions) {
        var jobService = jobs.getIfAvailable();
        if (jobService == null) return null;
        try { return jobService.regionProblem(conditions); }
        catch (DataAccessException ex) { return null; }
    }

    /** 바로 추천 흐름에서 마지막으로 만든 추천을 기억하고, 문장 분석에서 비운 칸이 있으면 그 추천 화면에서 알린다. */
    private static void rememberJob(HttpSession session, UUID id, List<String> notices) {
        session.setAttribute(LAST_JOB_KEY, id);
        if (notices.isEmpty()) session.removeAttribute(ANALYSIS_NOTICE_KEY);
        else session.setAttribute(ANALYSIS_NOTICE_KEY, new AnalysisNotice(id, notices));
    }

    /** 다른 탭에서 새로 시작해 이 입력의 토큰이 오래됐다. 보낸 내용을 입력 화면에 다시 채워 확인한 뒤 보내게 한다. */
    private String staleStart(TravelConditionForm form, HttpSession session, RedirectAttributes flash) {
        session.setAttribute(TravelConditionController.PREFILL_KEY, form);
        flash.addFlashAttribute("analysisError", "다른 화면에서 새로 시작해 입력을 다시 확인해 주세요. 보낸 내용은 그대로 채워 두었어요.");
        return "redirect:/travel/conditions";
    }

    /** 바로 추천 흐름에서 이 조건 수정 화면이 최신이 아니다. 마지막으로 만든 추천으로, 없으면 입력 화면으로 보낸다. */
    private String staleDirect(HttpSession session, RedirectAttributes flash) {
        if (session.getAttribute(LAST_JOB_KEY) instanceof UUID last) {
            flash.addFlashAttribute("jobNotice", "조건 수정 화면이 최신이 아니어서 가장 최근에 요청한 추천을 보여 드려요. 다른 조건으로 받으려면 '조건 수정하기'를 눌러 주세요.");
            return "redirect:/travel/recommendations/" + last;
        }
        flash.addFlashAttribute("analysisError", "다른 화면에서 조건을 바꿨어요. 입력 내용을 다시 확인해 주세요.");
        return "redirect:/travel/conditions";
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
