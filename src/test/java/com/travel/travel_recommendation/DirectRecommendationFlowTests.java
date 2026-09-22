package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
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
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class DirectRecommendationFlowTests {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @MockitoBean OpenAiConditionClient client;
    @MockitoBean RecommendationJobService jobs;
    private final UUID id = UUID.randomUUID();
    private ObjectNode analysis;

    @BeforeEach void setup() throws Exception {
        analysis = (ObjectNode)mapper.readTree(new ClassPathResource("ai/valid-conditions.json").getContentAsString(StandardCharsets.UTF_8));
        for(String field : List.of("region","startDate","endDate","durationDays","partySize","budget","accommodationNeeded","includeNearby","season","companion","transportation")) analysis.putNull(field);
        analysis.putArray("themes"); analysis.put("summary","상어 관람을 원하는 여행");
        analysis.put("travelPurpose","상어 관람");
        when(client.analyze(anyString(),anyMap())).thenReturn(analysis);
        when(jobs.create(any(),nullable(String.class),any())).thenReturn(id);
    }
    private String token(MockHttpSession session) throws Exception {
        mvc.perform(get("/travel/conditions").session(session)).andExpect(status().isOk());
        return (String)session.getAttribute("travelInputToken");
    }
    private void submit(MockHttpSession session,String token) throws Exception {
        mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf())
            .param("inputToken",token).param("inputMode","TEXT").param("originalText","상어 보고 싶어"))
            .andExpect(redirectedUrl("/travel/recommendations/"+id));
    }
    @Test void purposeOnlyInputGoesStraightToRecommendationAndDuplicatePostDoesNotPayAgain() throws Exception {
        var session=new MockHttpSession(); var token=token(session);
        submit(session,token); submit(session,token);
        var c=(ConfirmedTravelConditions)session.getAttribute("confirmedTravelConditions");
        assertThat(c.durationDays()).isNull(); assertThat(c.partySize()).isNull();
        assertThat(c.interpretationSummary()).isEqualTo("상어 관람을 원하는 여행");
        assertThat(c.directRecommendation()).isTrue();
        assertThat(c.travelPurpose()).isEqualTo("상어 관람");
        verify(client,times(1)).analyze(anyString(),anyMap());
        verify(jobs,times(1)).create(any(),nullable(String.class),any());
    }
    @Test void failedAnalysisCanBeRetriedAndNeverCreatesAJob() throws Exception {
        when(client.analyze(anyString(),anyMap())).thenThrow(new ConditionAnalysisException("분석 지연"));
        var session=new MockHttpSession(); var token=token(session);
        mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf()).param("inputToken",token)
            .param("inputMode","TEXT").param("originalText","상어 보고 싶어"))
            .andExpect(redirectedUrl("/travel/conditions")).andExpect(flash().attribute("analysisError","분석 지연"));
        assertThat(session.getAttribute("travelConditionAnalyzing")).isNull();
        verifyNoInteractions(jobs);
    }
    @Test void editsUseTheViewedRecommendationsSnapshotAndImmediatelyRecommendAgain() throws Exception {
        var session=new MockHttpSession(); submit(session,token(session));
        var c=(ConfirmedTravelConditions)session.getAttribute("confirmedTravelConditions");
        when(jobs.conditions(eq(id),nullable(String.class),any())).thenReturn(c);
        mvc.perform(get("/travel/conditions/edit").session(session).param("recommendationId",id.toString()))
            .andExpect(redirectedUrl("/travel/conditions/review"));
        var view=mvc.perform(get("/travel/conditions/review").session(session)).andExpect(status().isOk()).andReturn();
        var form=(ConditionReviewForm)view.getModelAndView().getModel().get("reviewForm");
        mvc.perform(post("/travel/conditions/confirm").session(session).with(csrf()).param("revision",form.getRevision()).param("region","부산"))
            .andExpect(redirectedUrl("/travel/recommendations/"+id));
        verify(jobs).create(argThat(value -> "부산".equals(value.region()) && value.directRecommendation()),nullable(String.class),any());
        verify(client,times(1)).analyze(anyString(),anyMap());
    }
    @Test void staleTokensAndCsrfCannotStartAnAnalysis() throws Exception {
        var session=new MockHttpSession(); var token=token(session);
        mvc.perform(post("/travel/conditions/recommend").session(session).param("inputToken",token)
            .param("inputMode","TEXT").param("originalText","상어 보고 싶어")).andExpect(status().isForbidden());
        mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf()).param("inputToken","old")
            .param("inputMode","TEXT").param("originalText","상어 보고 싶어")).andExpect(status().is3xxRedirection());
        verifyNoInteractions(client,jobs);
    }
}
