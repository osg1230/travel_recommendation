package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.TravelConditionController;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
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
    /** 요청 횟수 제한. 기본은 null(허용)을 돌려주고, 제한 안내를 시험할 때만 값을 정한다. */
    @MockitoBean AiRequestLimiter limiter;
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
        // 다른 탭에서 새로 시작해 토큰이 바뀌었으면 보낸 문장을 입력 화면에 다시 채우고 이유를 알린다.
        mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf()).param("inputToken","old")
            .param("inputMode","TEXT").param("originalText","상어 보고 싶어"))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("analysisError",containsString("다른 화면에서 새로 시작해 입력을 다시 확인해 주세요")));
        assertThat(((TravelConditionForm)session.getAttribute(TravelConditionController.PREFILL_KEY)).getOriginalText()).isEqualTo("상어 보고 싶어");
        mvc.perform(get("/travel/conditions").session(session)).andExpect(content().string(containsString("상어 보고 싶어")));
        verifyNoInteractions(client,jobs);
    }
    @Test void tooManyRequestsKeepTheInputAndSkipPaidAnalysis() throws Exception {
        when(limiter.tryAcquire(nullable(String.class),anyString())).thenReturn("요청이 많아 잠시 뒤 다시 시도해 주세요(약 12분 뒤).");
        var session=new MockHttpSession(); var token=token(session);
        mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf()).param("inputToken",token)
            .param("inputMode","TEXT").param("originalText","상어 보고 싶어"))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("analysisError","요청이 많아 잠시 뒤 다시 시도해 주세요(약 12분 뒤)."));
        assertThat(((TravelConditionForm)session.getAttribute(TravelConditionController.PREFILL_KEY)).getOriginalText()).isEqualTo("상어 보고 싶어");
        verifyNoInteractions(client,jobs);
    }
    @Test void unknownSubRegionOpensReviewWithRegionErrorInsteadOfCreatingAJob() throws Exception {
        analysis.put("region","제주 우도");
        when(jobs.regionProblem(any())).thenReturn("'제주 우도'에 맞는 여행지를 찾지 못했어요. 대신 '제주' 지역의 제주, 서귀포에서 찾을 수 있어요. 지역 칸을 '제주'처럼 고쳐 주세요.");
        var session=new MockHttpSession(); var token=token(session);
        var flashMap=mvc.perform(post("/travel/conditions/recommend").session(session).with(csrf()).param("inputToken",token)
            .param("inputMode","TEXT").param("originalText","우도 가고 싶어"))
            .andExpect(redirectedUrl("/travel/conditions/review")).andReturn().getFlashMap();
        var form=(ConditionReviewForm)mvc.perform(get("/travel/conditions/review").session(session).flashAttrs(flashMap))
            .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("reviewForm","region"))
            .andExpect(content().string(containsString("지역의 제주, 서귀포에서 찾을 수 있어요")))
            .andReturn().getModelAndView().getModel().get("reviewForm");
        verify(jobs,never()).create(any(),nullable(String.class),any());
        // 지역을 고치면 바로 추천을 요청한다.
        when(jobs.regionProblem(any())).thenReturn(null);
        mvc.perform(post("/travel/conditions/confirm").session(session).with(csrf()).param("revision",form.getRevision()).param("region","제주"))
            .andExpect(redirectedUrl("/travel/recommendations/"+id));
        verify(jobs).create(argThat(value -> "제주".equals(value.region())),nullable(String.class),any());
    }
    @Test void clearedAnalysisFieldsAreShownOnTheRecommendationPage() throws Exception {
        analysis.put("durationDays",10);
        var session=new MockHttpSession(); submit(session,token(session));
        var c=(ConfirmedTravelConditions)session.getAttribute("confirmedTravelConditions");
        assertThat(c.durationDays()).isNull();
        when(jobs.view(eq(id),nullable(String.class),any())).thenReturn(new RecommendationJobView(id,"QUEUED",
            "앞선 요청을 처리하고 있어요. 차례가 되면 바로 시작해요.",null,List.of(),true,List.of(),false,0,false,false));
        when(jobs.conditions(eq(id),nullable(String.class),any())).thenReturn(c);
        mvc.perform(get("/travel/recommendations/"+id).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("여행 일수는 7일까지만 정할 수 있어 비워 두었어요.")))
            .andExpect(content().string(containsString("앞선 요청을 처리하고 있어요.")))
            .andExpect(content().string(containsString("data-poll-seconds=\""+RecommendationJobService.POLL_SECONDS+"\"")));
    }
    @Test void outdatedEditFormShowsTheLatestRecommendationOrReturnsToInput() throws Exception {
        var session=new MockHttpSession(); submit(session,token(session));
        var c=(ConfirmedTravelConditions)session.getAttribute("confirmedTravelConditions");
        when(jobs.conditions(eq(id),nullable(String.class),any())).thenReturn(c);
        var old=reviewRevision(session);
        reviewRevision(session); // 다른 탭에서 수정 화면을 다시 열어 확인 토큰이 바뀌었다.
        mvc.perform(post("/travel/conditions/confirm").session(session).with(csrf()).param("revision",old).param("region","부산"))
            .andExpect(redirectedUrl("/travel/recommendations/"+id))
            .andExpect(flash().attribute("jobNotice",containsString("가장 최근에 요청한 추천을 보여 드려요")));
        verify(jobs,times(1)).create(any(),nullable(String.class),any());
        // 이 세션에서 만든 추천이 없으면 입력 화면으로 보낸다.
        var other=new MockHttpSession();
        old=reviewRevision(other); reviewRevision(other);
        mvc.perform(post("/travel/conditions/confirm").session(other).with(csrf()).param("revision",old))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("analysisError",containsString("다른 화면에서 조건을 바꿨어요")));
    }
    private String reviewRevision(MockHttpSession session) throws Exception {
        mvc.perform(get("/travel/conditions/edit").session(session).param("recommendationId",id.toString()))
            .andExpect(redirectedUrl("/travel/conditions/review"));
        var view=mvc.perform(get("/travel/conditions/review").session(session)).andExpect(status().isOk()).andReturn();
        return ((ConditionReviewForm)view.getModelAndView().getModel().get("reviewForm")).getRevision();
    }
    @Test void retryUsesThatJobsConditionsOnlyForTemporaryFailures() throws Exception {
        var newId=UUID.randomUUID();
        when(jobs.view(eq(id),nullable(String.class),any())).thenReturn(new RecommendationJobView(id,"FAILED",
            "시간이 너무 오래 걸렸어요.","TIMEOUT",List.of(),false,List.of(),false,0,false,true));
        when(jobs.retry(eq(id),nullable(String.class),any())).thenReturn(newId);
        var session=new MockHttpSession();
        for(int i=0;i<2;i++) mvc.perform(post("/travel/recommendations/"+id+"/retry").session(session).with(csrf()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isAccepted()).andExpect(jsonPath("$.location").value("/travel/recommendations/"+newId));
        // 두 번 눌러도 새 작업은 하나만 만든다.
        verify(jobs,times(1)).retry(eq(id),nullable(String.class),any());
        var noRetry=UUID.randomUUID();
        when(jobs.view(eq(noRetry),nullable(String.class),any())).thenReturn(new RecommendationJobView(noRetry,"FAILED",
            "여행지가 없어요.","NO_DESTINATIONS",List.of(),false,List.of(),false,0,false,false));
        mvc.perform(post("/travel/recommendations/"+noRetry+"/retry").session(session).with(csrf()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(containsString("같은 조건으로 다시 시도할 수 없어요")));
    }
    @Test void jobErrorsAreJsonForScriptsAndAFriendlyPageForBrowsers() throws Exception {
        var missing=UUID.randomUUID();
        when(jobs.view(eq(missing),nullable(String.class),any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));
        mvc.perform(post("/travel/recommendations/"+missing+"/retry").with(csrf()).accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isNotFound()).andExpect(jsonPath("$.message").value("요청을 찾을 수 없거나 만료됐어요."));
        mvc.perform(get("/travel/recommendations/"+missing).accept(MediaType.TEXT_HTML))
            .andExpect(status().isNotFound()).andExpect(view().name("travel/recommendation-error"))
            .andExpect(content().string(containsString("여행지 추천을 열 수 없어요")));
        // DB 오류는 내부 내용을 숨기고 다시 시도하라고 안내한다.
        when(jobs.view(eq(id),nullable(String.class),any())).thenThrow(new DataAccessResourceFailureException("db down"));
        mvc.perform(get("/travel/recommendations/"+id+"/status").accept(MediaType.APPLICATION_JSON))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.message").value("추천 정보를 처리하지 못했어요. 잠시 후 다시 시도해 주세요."));
        mvc.perform(get("/travel/recommendations/"+id))
            .andExpect(status().isServiceUnavailable()).andExpect(view().name("travel/recommendation-error"))
            .andExpect(content().string(not(containsString("db down"))));
    }
}
