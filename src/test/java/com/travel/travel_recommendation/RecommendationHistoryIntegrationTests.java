package com.travel.travel_recommendation;

import com.travel.travel_recommendation.service.KoreaTime;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 회원 추천 이력 목록·상세·삭제(SFR-14-02~04)를 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class RecommendationHistoryIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired RecommendationHistoryService history;
    @Autowired ItineraryJobService schedules;
    @Autowired SavedItineraryService saved;
    @Autowired AiJobRepository jobs;
    @Autowired RecommendationRequestRepository requests;
    @Autowired RecommendationCandidateRepository candidates;
    @Autowired ItineraryRepository itineraries;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    // 장소 수집(refresh)이 실제 관광공사·GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 빈 목록을 돌려준다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    private User owner;

    @BeforeEach void setup() {
        owner=member();
        when(maps.locate(anyString())).thenAnswer(call->new KakaoMapClient.Location(call.getArgument(0),128.9+(Math.abs(call.getArgument(0).hashCode()%1000))/100000.0,37.7,Instant.now()));
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
    }
    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        return users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
    }
    private ConfirmedTravelConditions conditions(String text) {
        var start=LocalDate.now().plusDays(10);
        return new ConfirmedTravelConditions("TEXT",text,"강릉",start,start.plusDays(1),2,new BigDecimal("300000"),2,true,false,List.of(),"바다 위주로 해석");
    }
    /** 추천을 완료 상태로 만든다. GPT 대신 조회된 첫 후보를 1위로 저장한다. */
    private UUID completed(User user,String text) {
        var job=recommendations.create(conditions(text),user.getLoginId(),null);
        recommendations.prepare(job);
        var first=recommendations.view(job,user.getLoginId(),null).candidates().getFirst();
        var entity=jobs.findById(job).orElseThrow();
        candidates.save(new RecommendationCandidate(entity.getRequestId(),first.placeId(),1,"바다를 볼 수 있어요.",mapper.writeValueAsString(first)));
        entity.completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,first,"바다를 볼 수 있어요."))));
        em.flush();
        return job;
    }
    // -Dtravel.ui.capture=true 일 때만 화면 확인용 HTML을 target/ui-check에 남긴다.
    private static void capture(String name,String html) throws java.io.IOException {
        if(!Boolean.getBoolean("travel.ui.capture")) return;
        var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
        java.nio.file.Files.writeString(folder.resolve(name),html);
    }
    private Long requestId(UUID job) { return jobs.findById(job).orElseThrow().getRequestId(); }

    @Test void listsNewestFirstWithResultsAndShowsOnlyOwnHistory() throws Exception {
        var older=completed(owner,"오래된 요청");
        var pending=recommendations.create(conditions("처리 중 요청"),owner.getLoginId(),null);
        em.flush();
        jdbcCreatedAt(requestId(older),KoreaTime.now().minusDays(1));
        var list=history.list(owner.getLoginId());
        assertThat(list).extracting(RecommendationHistory.Summary::id).containsExactly(requestId(pending),requestId(older));
        assertThat(list.get(0).status()).isEqualTo("PENDING");
        assertThat(list.get(1).status()).isEqualTo("DONE");
        assertThat(list.get(1).destinations()).hasSize(1);
        assertThat(history.list(member().getLoginId())).isEmpty();
    }
    private void jdbcCreatedAt(Long id,LocalDateTime at) {
        em.createNativeQuery("UPDATE dbo.recommendation_requests SET created_at=? WHERE request_id=?").setParameter(1,at).setParameter(2,id).executeUpdate();
        em.clear();
    }

    @Test void detailShowsConditionsReasonsAndResultLinkButNotToOthers() {
        var job=completed(owner,"바다 보면서 쉬고 싶어요");
        var detail=history.detail(requestId(job),owner.getLoginId());
        assertThat(detail.originalText()).isEqualTo("바다 보면서 쉬고 싶어요");
        assertThat(detail.interpretationSummary()).isEqualTo("바다 위주로 해석");
        assertThat(detail.conditions()).anySatisfy(c->{ assertThat(c.label()).isEqualTo("예산"); assertThat(c.value()).isEqualTo("300,000원"); });
        assertThat(detail.conditions()).anySatisfy(c->{ assertThat(c.label()).isEqualTo("인원"); assertThat(c.value()).isEqualTo("2명"); });
        assertThat(detail.destinations()).singleElement().satisfies(d->{ assertThat(d.rank()).isEqualTo(1); assertThat(d.reason()).isEqualTo("바다를 볼 수 있어요."); });
        assertThat(detail.resultJobId()).isEqualTo(job);
        var other=member();
        assertThatThrownBy(()->history.detail(requestId(job),other.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("찾을 수 없어요");
        // 추천 결과 화면 보관 기간이 지나면 이력은 보이되 결과 화면 링크는 주지 않는다.
        jobs.findById(job).orElseThrow(); em.createNativeQuery("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSDATETIME()) WHERE job_id=?")
            .setParameter(1,job.toString()).executeUpdate(); em.clear();
        assertThat(history.detail(requestId(job),owner.getLoginId()).resultJobId()).isNull();
    }

    @Test void deleteRemovesHistoryAndJobsButKeepsSavedItinerary() {
        var job=completed(owner,"삭제할 요청");
        Long request=requestId(job);
        var destination=recommendations.view(job,owner.getLoginId(),null).recommendations().getFirst().destination().placeId();
        var draft=schedules.create(job,destination,owner.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        // 처리 중인 일정 작업이 있으면 지우지 않는다.
        assertThatThrownBy(()->history.delete(request,owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("처리 중");
        var work=schedules.claim(draft); schedules.complete(work,schedules.generate(work)); em.flush();
        var itinerary=saved.save(draft,owner.getLoginId()).id();
        em.flush(); em.clear();
        assertThatThrownBy(()->history.delete(request,member().getLoginId())).isInstanceOf(ResponseStatusException.class);

        history.delete(request,owner.getLoginId());
        em.flush(); em.clear();
        assertThat(requests.findById(request)).isEmpty();
        assertThat(candidates.findByRequestIdOrderByRank(request)).isEmpty();
        assertThat(jobs.findById(job)).isEmpty(); assertThat(jobs.findById(draft)).isEmpty();
        var kept=itineraries.findById(itinerary).orElseThrow();
        assertThat(kept.getRequestId()).isNull();
        assertThat(saved.detail(itinerary,owner.getLoginId()).days()).isNotEmpty();
        assertThat(history.list(owner.getLoginId())).isEmpty();
    }

    @Test void sameConditionsCanBeRecommendedAgainOrFixedFirstWhenOutdated() throws Exception {
        var job=completed(owner,"다시 추천받을 요청");
        Long request=requestId(job);
        assertThat(history.detail(request,owner.getLoginId()).repeatable()).isTrue();
        mvc.perform(get("/my/recommendations/"+request).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("같은 조건으로 다시 추천받기")));
        // 지금도 쓸 수 있는 조건이면 새 추천을 바로 요청하고, 새 이력으로 남긴다.
        var session=new org.springframework.mock.web.MockHttpSession();
        String location=mvc.perform(post("/my/recommendations/"+request+"/again").session(session).with(user(owner.getLoginId())).with(csrf()))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        assertThat(location).startsWith("/travel/recommendations/");
        UUID again=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertThat(again).isNotEqualTo(job);
        var c=recommendations.conditions(again,owner.getLoginId(),null);
        assertThat(c.region()).isEqualTo("강릉");
        assertThat(c.originalText()).isEqualTo("다시 추천받을 요청");
        assertThat(c.directRecommendation()).isTrue();
        assertThat(requestId(again)).isNotEqualTo(request);

        // 지난 출발일처럼 지금은 쓸 수 없는 값이 있으면 새 추천을 만들지 않고 조건 수정 화면에서 고치게 한다.
        var start=LocalDate.now().minusDays(3);
        var past=recommendations.create(new ConfirmedTravelConditions("TEXT","지난 여행","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(),""),owner.getLoginId(),null);
        em.flush();
        long jobsBefore=jobs.count();
        var other=new org.springframework.mock.web.MockHttpSession();
        var flash=mvc.perform(post("/my/recommendations/"+requestId(past)+"/again").session(other).with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/travel/conditions/review")).andReturn().getFlashMap();
        assertThat(jobs.count()).isEqualTo(jobsBefore);
        mvc.perform(get("/travel/conditions/review").session(other).with(user(owner.getLoginId())).flashAttrs(flash))
            .andExpect(status().isOk())
            .andExpect(model().attributeHasFieldErrors("reviewForm","startDate"))
            .andExpect(content().string(containsString("출발일은 오늘이나 그 이후 날짜로 골라 주세요.")))
            .andExpect(content().string(containsString("지난 여행")));
        // 다른 회원의 이력으로는 다시 추천할 수 없다.
        mvc.perform(post("/my/recommendations/"+request+"/again").with(user(member().getLoginId())).with(csrf()))
            .andExpect(status().isNotFound());
    }

    /** 일정 만들기 요청도 앞선 요청을 기다리는 시간(5분)과 처리 시간을 따로 잰다. 3분 기다린 요청은 처리하고 6분 기다린 요청은 다시 요청하게 한다. */
    @Test void itineraryRequestsWaitUpToFiveMinutesBeforeTimingOut() {
        var first=completed(owner,"일정 대기 확인");
        var destination=recommendations.view(first,owner.getLoginId(),null).recommendations().getFirst().destination().placeId();
        var waited=schedules.create(first,destination,owner.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        em.flush();
        em.createNativeQuery("UPDATE dbo.ai_jobs SET created_at=DATEADD(minute,-3,SYSDATETIME()) WHERE job_id=?").setParameter(1,waited.toString()).executeUpdate(); em.clear();
        assertThat(schedules.claim(waited)).isNotNull();
        var second=completed(owner,"오래 기다린 일정");
        var late=schedules.create(second,destination,owner.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        em.flush();
        em.createNativeQuery("UPDATE dbo.ai_jobs SET created_at=DATEADD(minute,-6,SYSDATETIME()) WHERE job_id=?").setParameter(1,late.toString()).executeUpdate(); em.clear();
        assertThat(schedules.claim(late)).isNull();
        var failed=jobs.findById(late).orElseThrow();
        assertThat(failed.getErrorCode()).isEqualTo("TIMEOUT");
        assertThat(failed.getMessage()).isEqualTo(RecommendationJobService.WAIT_MESSAGE);
    }

    @Test void screensRequireLoginAndRenderListDetailAndDelete() throws Exception {
        var job=completed(owner,"화면 확인");
        Long request=requestId(job);
        mvc.perform(get("/my/recommendations")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/my/recommendations").with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("추천 완료")))
            .andExpect(content().string(containsString("/my/recommendations/"+request)))
            .andDo(response->capture("recommendations.html",response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)));
        mvc.perform(get("/my/recommendations/"+request).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andDo(response->capture("recommendation.html",response.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)))
            .andExpect(content().string(containsString("화면 확인")))
            .andExpect(content().string(containsString("바다를 볼 수 있어요.")))
            .andExpect(content().string(containsString("/travel/recommendations/"+job)));
        mvc.perform(get("/my/recommendations/"+request).with(user(member().getLoginId()))).andExpect(status().isNotFound());
        mvc.perform(post("/my/recommendations/"+request+"/delete").with(user(owner.getLoginId()))).andExpect(status().isForbidden());
        mvc.perform(post("/my/recommendations/"+request+"/delete").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/my/recommendations"));
        mvc.perform(get("/my/recommendations").with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("/my/recommendations/"+request))));
    }
}
