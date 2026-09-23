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
    @Test void rejectsMalformedAiFieldsAndConditionConflicts() {
        TravelConditionForm source = new TravelConditionForm();
        source.setOriginalText("강릉 여행");
        for (String invalidJson : new String[]{"null", "[]", "{}"}) {
            when(client.analyze(anyString(), anyMap())).thenReturn(mapper.readTree(invalidJson));
            assertThatThrownBy(() -> service.analyze(source, options.getChoices())).isInstanceOf(ConditionAnalysisException.class);
        }
        ObjectNode[] invalid = new ObjectNode[]{result.deepCopy().put("durationDays", "3"),
                result.deepCopy().put("partySize", -1), result.deepCopy().put("extra", "unexpected"),
                result.deepCopy().put("season", "허용되지 않음"), result.deepCopy().put("budget", -1),
                result.deepCopy().put("startDate", "2026-10-01").put("endDate", "2026-10-02"),
                result.deepCopy().put("startDate", "2026-02-30").put("endDate", "2026-03-01"),
                result.deepCopy().put("isTravel", false), result.deepCopy().put("includeNearby", "true")};
        for (ObjectNode value : invalid) {
            when(client.analyze(anyString(), anyMap())).thenReturn(value);
            assertThatThrownBy(() -> service.analyze(source, options.getChoices())).isInstanceOf(ConditionAnalysisException.class);
        }
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
}
