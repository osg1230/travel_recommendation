package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ConditionReviewTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired ConditionReviewService service;
    @Autowired TravelConditionOptions options;
    @MockitoBean OpenAiConditionClient client;
    private ObjectNode result;
    private static String day(int daysFromToday) { return java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(daysFromToday).toString(); }

    @BeforeEach void response() throws Exception {
        result = (ObjectNode) mapper.readTree(new ClassPathResource("ai/valid-conditions.json").getContentAsString(StandardCharsets.UTF_8));
        when(client.analyze(anyString(), anyMap())).thenReturn(result);
    }
    private TravelConditionForm input(MockHttpSession session, String text) throws Exception {
        mvc.perform(post("/travel/conditions").with(csrf()).session(session)
                .param("inputMode", "TEXT").param("originalText", text)).andExpect(status().is3xxRedirection());
        return (TravelConditionForm) session.getAttribute("travelConditionDraft");
    }
    private ConditionReviewForm analyze(MockHttpSession session) throws Exception {
        var draft = input(session, "친구와 강릉 2박 3일 바다 여행");
        mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", draft.getDraftId()))
                .andExpect(redirectedUrl("/travel/conditions/review"));
        return (ConditionReviewForm) mvc.perform(get("/travel/conditions/review").session(session))
                .andExpect(status().isOk()).andExpect(content().string(containsString("강릉")))
                .andReturn().getModelAndView().getModel().get("reviewForm");
    }
    @Test void textAnalysisCanBeCorrectedConfirmedAndIsolated() throws Exception {
        MockHttpSession session = new MockHttpSession();
        ConditionReviewForm form = analyze(session);
        assertThat(form.getDurationDays()).isEqualTo("3");
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session)
                        .param("revision", form.getRevision()).param("region", " 강릉 경포 ")
                        .param("durationDays", "4").param("partySize", "3").param("budget", "900000")
                        .param("themes", "바다").param("accommodationNeeded", "true").param("includeNearby", "false")
                        .param("inputMode", "TEXT").param("originalText", "변조").param("selectedKeywords[0].name", "변조"))
                .andExpect(redirectedUrl("/travel/conditions/confirmed"));
        var confirmed = (ConfirmedTravelConditions) session.getAttribute("confirmedTravelConditions");
        assertThat(confirmed.region()).isEqualTo("강릉 경포");
        assertThat(confirmed.durationDays()).isEqualTo(4);
        assertThat(confirmed.partySize()).isEqualTo(3);
        assertThat(confirmed.originalText()).isEqualTo("친구와 강릉 2박 3일 바다 여행");
        assertThat(confirmed.keywordNames("THEME")).isEqualTo("바다");
        assertThat(confirmed.includeNearby()).isFalse();
        mvc.perform(get("/travel/conditions/confirmed").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("강릉 경포")));
        mvc.perform(get("/travel/conditions/confirmed").session(new MockHttpSession()))
                .andExpect(redirectedUrl("/travel/conditions"));
        verify(client, times(1)).analyze(anyString(), anyMap());
    }
    // 문장에서 읽은 분위기·속도는 다시 묻지 않고 확인 화면에 미리 골라 두며, 확정하면 MOOD·PACE 키워드로 남는다.
    @Test void moodAndPaceReadFromTheSentenceArePreselectedAndConfirmed() throws Exception {
        result.putArray("moods").add("한적한").add("없는 분위기");
        result.put("pace", "느긋하게");
        MockHttpSession session = new MockHttpSession();
        ConditionReviewForm form = analyze(session);
        // 목록에 없는 값이 섞이면 분위기 칸만 비우고 안내한다(다른 칸처럼).
        assertThat(form.getMoods()).isEmpty();
        assertThat(form.getPace()).isEqualTo("느긋하게");
        result.putArray("moods").add("한적한").add("감성적인");
        session = new MockHttpSession();
        form = analyze(session);
        assertThat(form.getMoods()).containsExactly("한적한", "감성적인");
        mvc.perform(get("/travel/conditions/review").session(session))
                .andExpect(status().isOk());
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision())
                        .param("region", "강릉").param("moods", "감성적인").param("pace", "알차게"))
                .andExpect(redirectedUrl("/travel/conditions/confirmed"));
        var confirmed = (ConfirmedTravelConditions) session.getAttribute("confirmedTravelConditions");
        assertThat(confirmed.keywordNames("MOOD")).isEqualTo("감성적인");
        assertThat(confirmed.keywordNames("PACE")).isEqualTo("알차게");
    }
    @Test void keywordFlowAndMissingConditionsDoNotCallAi() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").with(csrf()).session(session));
        var view = mvc.perform(get("/travel/conditions/review").session(session)).andExpect(status().isOk()).andReturn();
        var form = (ConditionReviewForm) view.getModelAndView().getModel().get("reviewForm");
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision()))
                .andExpect(redirectedUrl("/travel/conditions/confirmed"));
        var confirmed = (ConfirmedTravelConditions) session.getAttribute("confirmedTravelConditions");
        assertThat(confirmed.durationDays()).isNull();
        assertThat(confirmed.budget()).isNull();
        assertThat(confirmed.accommodationNeeded()).isNull();
        verifyNoInteractions(client);
    }
    @Test void invalidUserCorrectionsDoNotOverwriteConfirmedConditions() throws Exception {
        MockHttpSession session = new MockHttpSession();
        ConditionReviewForm form = analyze(session);
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision())
                        .param("startDate", "2026-10-03").param("endDate", "2026-10-01")
                        .param("partySize", "0").param("budget", "-1").param("includeNearby", "yes"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("reviewForm", "endDate", "partySize", "budget", "includeNearby"));
        assertThat(session.getAttribute("confirmedTravelConditions")).isNull();
    }
    @Test void reloadingAndDuplicateAnalysisReuseSuccessfulResult() throws Exception {
        MockHttpSession session = new MockHttpSession();
        analyze(session);
        var draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", draft.getDraftId()))
                .andExpect(redirectedUrl("/travel/conditions/review"));
        mvc.perform(get("/travel/conditions/review").session(session)).andExpect(status().isOk());
        verify(client, times(1)).analyze(anyString(), anyMap());
    }
    @Test void replacingInputInvalidatesReviewAndOldConfirmationToken() throws Exception {
        MockHttpSession session = new MockHttpSession();
        ConditionReviewForm form = analyze(session);
        input(session, "제주 당일 여행");
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision()))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        assertThat(session.getAttribute("confirmedTravelConditions")).isNull();
        mvc.perform(get("/travel/conditions/review").session(session)).andExpect(redirectedUrl("/travel/conditions/check"));
    }
    @Test void nightsInTextMeanAccommodationIsNeededWhenAiLeftItBlank() {
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().putNull("accommodationNeeded"));
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("부모님 모시고 2박 3일로 조용한 바닷가");
        assertThat(service.analyze(source, options.getChoices()).form().getAccommodationNeeded()).isEqualTo("true");
        source.setOriginalText("강릉 바다 여행");
        assertThat(service.analyze(source, options.getChoices()).form().getAccommodationNeeded()).isEmpty();
    }
    @Test void fillsBlankPurposeWithAiSuggestionAndBlankSeasonWithCurrentSeason() {
        var vague = result.deepCopy().put("travelPurpose", "").put("suggestedConcept", "시원한 계곡 물놀이와 숲길 산책");
        vague.putNull("season");
        when(client.analyze(anyString(), anyMap())).thenReturn(vague);
        TravelConditionForm source = new TravelConditionForm();
        source.setInputMode("TEXT");
        source.setOriginalText("그냥 아무 데나 가고 싶어");
        var analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getTravelPurpose()).isEqualTo("시원한 계곡 물놀이와 숲길 산책");
        assertThat(analysis.form().isPurposeSuggested()).isTrue();
        // 계절은 서버가 오늘 날짜로 채운다.
        String season = ConditionReviewService.seasonName(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getMonthValue());
        var confirmed = service.confirm(analysis.form(), source, analysis.summary(), options.getChoices());
        assertThat(confirmed.keywordNames("SEASON")).isEqualTo(season);
        assertThat(confirmed.purposeSuggested()).isTrue();
        assertThat(confirmed.purposeLabel()).isEqualTo("AI가 제안한 여행 방향");
        assertThat(ConditionReviewService.seasonName(7)).isEqualTo("여름");
        assertThat(ConditionReviewService.seasonName(12)).isEqualTo("겨울");
    }
    @Test void excludedRegionsAreKeptAndMalformedOnesRejected() {
        var excluding = result.deepCopy();
        excluding.putArray("excludedRegions").add("부산").add("부산").add("강릉");
        when(client.analyze(anyString(), anyMap())).thenReturn(excluding);
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("부산 말고 강릉으로");
        var analysis = service.analyze(source, options.getChoices());
        // 중복과, 가고 싶다고 한 지역(강릉)과 같은 값은 버린다.
        assertThat(analysis.form().getExcludedRegions()).containsExactly("부산");
        assertThat(service.confirm(analysis.form(), source, "", options.getChoices()).excludedRegions()).containsExactly("부산");
        // 지역 이름이 아닌 값은 뺄 지역만 비우고 알린다. 다른 조건은 그대로 쓴다.
        var malformed = result.deepCopy();
        malformed.putArray("excludedRegions").add("<b>부산</b>");
        when(client.analyze(anyString(), anyMap())).thenReturn(malformed);
        var cleared = service.analyze(source, options.getChoices());
        assertThat(cleared.form().getExcludedRegions()).isEmpty();
        assertThat(cleared.form().getRegion()).isEqualTo("강릉");
        assertThat(cleared.notices()).containsExactly("추천에서 뺄 지역은 해석하지 못해 비워 두었어요.");
        // 문자열이 아닌 값은 응답 형식이 틀린 것이라 전체를 반영하지 않는다.
        var wrongType = result.deepCopy();
        wrongType.putArray("excludedRegions").add(3);
        when(client.analyze(anyString(), anyMap())).thenReturn(wrongType);
        assertThatThrownBy(() -> service.analyze(source, options.getChoices())).isInstanceOf(ConditionAnalysisException.class);
    }
    @Test void userPurposeIsKeptEvenWhenAiAlsoSuggestsConcept() {
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("suggestedConcept", "해수욕장에서 바다 즐기기"));
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("강릉 바다 여행");
        var form = service.analyze(source, options.getChoices()).form();
        assertThat(form.getTravelPurpose()).isEqualTo("바다 구경");
        assertThat(form.isPurposeSuggested()).isFalse();
    }
    @Test void rejectsMalformedAiFieldsAndConditionConflicts() {
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("강릉 여행");
        for (String invalidJson : new String[]{"null", "[]", "{}"}) {
            when(client.analyze(anyString(), anyMap())).thenReturn(mapper.readTree(invalidJson));
            assertThatThrownBy(() -> service.analyze(source, options.getChoices())).isInstanceOf(ConditionAnalysisException.class);
        }
        // 응답 형식(자료형·항목)이 틀리거나 여행 문장이 아니면 전체를 반영하지 않는다.
        ObjectNode[] invalid = new ObjectNode[]{result.deepCopy().put("durationDays", "3"),
                result.deepCopy().put("extra", "unexpected"), result.deepCopy().put("isTravel", false),
                result.deepCopy().put("includeNearby", "true"), result.deepCopy().put("travelPurpose", 3)};
        for (ObjectNode value : invalid) {
            when(client.analyze(anyString(), anyMap())).thenReturn(value);
            assertThatThrownBy(() -> service.analyze(source, options.getChoices())).isInstanceOf(ConditionAnalysisException.class);
        }
    }
    /** 형식은 맞지만 값이 맞지 않는 칸은 그 칸만 비우고 안내한 뒤 나머지 조건으로 진행한다. */
    @Test void invalidAiValuesClearOnlyThatFieldWithNotice() {
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("강릉 여행");
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("partySize", -1).put("budget", -1));
        var analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getPartySize()).isEmpty();
        assertThat(analysis.form().getBudget()).isEmpty();
        assertThat(analysis.form().getRegion()).isEqualTo("강릉");
        assertThat(analysis.form().getDurationDays()).isEqualTo("3");
        assertThat(analysis.notices()).containsExactlyInAnyOrder("전체 인원은 해석하지 못해 비워 두었어요.", "예산은 해석하지 못해 비워 두었어요.");

        // 일정은 최대 7일까지라 10일은 비우고 알린다.
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("durationDays", 10));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getDurationDays()).isEmpty();
        assertThat(analysis.notices()).contains("여행 일수는 7일까지만 정할 수 있어 비워 두었어요. 일정은 최대 7일까지 만들어요.");

        // 지난 출발일과 없는 날짜는 여행 날짜만 비운다.
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("startDate", day(-5)).put("endDate", day(-3)));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getStartDate()).isEmpty();
        assertThat(analysis.form().getEndDate()).isEmpty();
        assertThat(analysis.form().getDurationDays()).isEqualTo("3");
        assertThat(analysis.notices()).contains("출발일이 이미 지난 날짜라 여행 날짜를 비워 두었어요.");
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("startDate", "2026-02-30").put("endDate", "2026-03-01"));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getStartDate()).isEmpty();
        assertThat(analysis.notices()).contains("여행 날짜는 해석하지 못해 비워 두었어요.");

        // 날짜와 일수가 다르면 날짜를 기준으로 일수를 다시 계산한다.
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("startDate", day(10)).put("endDate", day(11)));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getStartDate()).isEqualTo(day(10));
        assertThat(analysis.form().getDurationDays()).isEqualTo("2");
        assertThat(analysis.notices()).contains("여행 일수가 날짜와 달라 날짜를 기준으로 다시 계산했어요.");

        // 목록에 없는 계절은 출발일(없으면 오늘)의 계절로 다시 정한다.
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("season", "허용되지 않음"));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getSeason()).isEqualTo(ConditionReviewService.seasonName(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).getMonthValue()));
        assertThat(analysis.notices()).containsExactly("계절은 해석하지 못해 출발일(없으면 오늘) 기준으로 다시 정했어요.");
    }
    /** 여행 목적의 숫자는 "100대 명산"처럼 쓸 수 있고, 계좌·전화번호처럼 6자리 이상 이어진 숫자만 막는다. */
    @Test void purposeAllowsShortNumbersButClearsLongNumbers() {
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("100대 명산 오르기");
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("travelPurpose", "100대 명산 오르기"));
        var analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getTravelPurpose()).isEqualTo("100대 명산 오르기");
        assertThat(analysis.notices()).isEmpty();
        when(client.analyze(anyString(), anyMap())).thenReturn(result.deepCopy().put("travelPurpose", "1234567 번호로 연락하기"));
        analysis = service.analyze(source, options.getChoices());
        assertThat(analysis.form().getTravelPurpose()).isEmpty();
        assertThat(analysis.form().getRegion()).isEqualTo("강릉");
        assertThat(analysis.notices()).containsExactly("여행 목적은 해석하지 못해 비워 두었어요.");
    }
    /** 조건 보완 화면에서 여행 목적을 고치면 AI 제안 표시를 지우고, 체크를 푼 뺄 지역은 추천 조건에서 빠진다. */
    @Test void reviewCanEditPurposeAndRemoveExcludedRegions() throws Exception {
        var vague = result.deepCopy().put("travelPurpose", "").put("suggestedConcept", "시원한 계곡 물놀이");
        vague.putArray("excludedRegions").add("부산").add("속초");
        when(client.analyze(anyString(), anyMap())).thenReturn(vague);
        MockHttpSession session = new MockHttpSession();
        ConditionReviewForm form = analyze(session);
        assertThat(form.isPurposeSuggested()).isTrue();
        // 제안 그대로 확정하면 제안 표시가 남는다.
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision())
                        .param("region", "강릉").param("travelPurpose", "시원한 계곡 물놀이").param("excludedRegions", "부산", "속초"))
                .andExpect(redirectedUrl("/travel/conditions/confirmed"));
        var confirmed = (ConfirmedTravelConditions) session.getAttribute("confirmedTravelConditions");
        assertThat(confirmed.purposeSuggested()).isTrue();
        assertThat(confirmed.excludedRegions()).containsExactly("부산", "속초");
        // 목적을 고치고 속초 체크를 풀면 사용자 목적으로 바뀌고 속초는 다시 후보에 들어간다. 화면에서 새 지역을 더할 수는 없다.
        form = (ConditionReviewForm) mvc.perform(get("/travel/conditions/review").session(session))
                .andExpect(content().string(containsString("부산 빼기")))
                .andReturn().getModelAndView().getModel().get("reviewForm");
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision())
                        .param("region", "강릉").param("travelPurpose", "바다 보며 쉬기").param("excludedRegions", "부산", "제주"))
                .andExpect(redirectedUrl("/travel/conditions/confirmed"));
        confirmed = (ConfirmedTravelConditions) session.getAttribute("confirmedTravelConditions");
        assertThat(confirmed.travelPurpose()).isEqualTo("바다 보며 쉬기");
        assertThat(confirmed.purposeSuggested()).isFalse();
        assertThat(confirmed.excludedRegions()).containsExactly("부산");
        // 주소·긴 숫자가 들어간 목적은 고쳐 달라고 한다.
        form = (ConditionReviewForm) mvc.perform(get("/travel/conditions/review").session(session))
                .andReturn().getModelAndView().getModel().get("reviewForm");
        mvc.perform(post("/travel/conditions/confirm").with(csrf()).session(session).param("revision", form.getRevision())
                        .param("travelPurpose", "https://example.com"))
                .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("reviewForm", "travelPurpose"));
    }
    @Test void failureRetainsSourceAndAllowsExplicitRetry() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var source = input(session, "강릉 여행");
        when(client.analyze(anyString(), anyMap())).thenThrow(new ConditionAnalysisException("분석 시간이 오래 걸려 중단했어요."));
        mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", source.getDraftId()))
                .andExpect(redirectedUrl("/travel/conditions/check")).andExpect(flash().attributeExists("analysisError"));
        assertThat(session.getAttribute("travelConditionDraft")).isSameAs(source);
        doReturn(result).when(client).analyze(anyString(), anyMap());
        mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", source.getDraftId()))
                .andExpect(redirectedUrl("/travel/conditions/review"));
    }
    @Test void csrfAndSourceTokenAreRequired() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var source = input(session, "강릉 여행");
        mvc.perform(post("/travel/conditions/analyze").session(session).param("draftId", source.getDraftId()))
                .andExpect(status().isForbidden());
        mvc.perform(post("/travel/conditions/confirm").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", "other"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        verifyNoInteractions(client);
    }
    @Test void concurrentAnalysisCannotOverwriteNewInputOrChargeTwice() throws Exception {
        MockHttpSession session = new MockHttpSession();
        var source = input(session, "강릉 여행");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(client.analyze(anyString(), anyMap())).thenAnswer(call -> {
            started.countDown();
            if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timed out");
            return result;
        });
        try (var executor = Executors.newSingleThreadExecutor()) {
            Future<?> first = executor.submit(() -> {
                try {
                    mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", source.getDraftId()))
                            .andExpect(redirectedUrl("/travel/conditions/check"));
                } catch (Exception ex) { throw new RuntimeException(ex); }
            });
            try {
                assertThat(started.await(3, TimeUnit.SECONDS)).isTrue();
                mvc.perform(post("/travel/conditions/analyze").with(csrf()).session(session).param("draftId", source.getDraftId()))
                        .andExpect(redirectedUrl("/travel/conditions/check"));
                input(session, "제주 여행");
            } finally { release.countDown(); }
            first.get(5, TimeUnit.SECONDS);
        }
        assertThat(session.getAttribute("travelConditionReview")).isNull();
        verify(client, times(1)).analyze(anyString(), anyMap());
    }

    /** 조건 고르기(키워드)에서는 출발일이 있을 때만 그 달의 계절을 채우고, 직접 고른 계절은 바꾸지 않는다. */
    @Test void keywordInputFillsSeasonFromStartDateOnly() {
        var choices = java.util.Map.of("season", java.util.List.of(new TravelKeyword(7L, "SEASON", "가을", null)));
        var draft = new TravelConditionForm();
        draft.setStartDate("2026-10-15");
        assertThat(service.fromKeywords(draft, choices).getSeason()).isEqualTo("7");
        draft.setStartDate("");
        assertThat(service.fromKeywords(draft, choices).getSeason()).isEmpty();
        draft.setStartDate("2026-10-15");
        draft.setSeason("3");
        assertThat(service.fromKeywords(draft, choices).getSeason()).isEqualTo("3");
    }
}
