package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class DestinationRecommendationIntegrationTests {
    @Autowired RecommendationJobService jobs;
    @Autowired DestinationRecommendationService service;
    @Autowired AiJobRepository jobRepository;
    @Autowired UserRepository users;
    @Autowired RecommendationCandidateRepository results;
    @Autowired com.travel.travel_recommendation.repository.RecommendationRequestRepository requests;
    @Autowired MockMvc mvc;
    @MockitoSpyBean OpenAiRecommendationClient client;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired jakarta.persistence.EntityManager em;
    private final byte[] guestHash=new byte[32];
    @BeforeEach void configured() { doReturn(true).when(client).configured(); }
    private UUID prepared(String region,String loginId) {
        var c=new ConfirmedTravelConditions("KEYWORD","",region,null,null,3,null,2,true,null,List.of(),"");
        UUID id=jobs.create(c,loginId,loginId==null?guestHash:null);jobs.prepare(id);return id;
    }
    private List<RankedDestination> ranked(DestinationRecommendationService.Work work) {
        List<RankedDestination> list=new ArrayList<>();
        for(var c:work.candidates()) list.add(new RankedDestination(list.size()+1,c,"확정 조건과 지역 소개를 바탕으로 비교한 후보입니다. 비용은 확인이 필요합니다."));
        return list;
    }
    @Test void memberResultsAndCompletionAreSavedTogetherAndDuplicateCompletionIsIgnored() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var member=users.saveAndFlush(new User("rank_"+suffix,"unused-hash","추천"+suffix));
        UUID id=prepared("",member.getLoginId());
        service.enqueue(id,member.getLoginId(),null,0);service.enqueue(id,member.getLoginId(),null,0);
        var work=service.claim(id);assertThat(service.claim(id)).isNull();
        service.complete(work,ranked(work));service.complete(work,ranked(work));
        var job=jobRepository.findById(id).orElseThrow();
        assertThat(job.getStatus()).isEqualTo("COMPLETED");assertThat(job.getAttemptCount()).isEqualTo((short)1);
        assertThat(results.findByRequestIdOrderByRank(job.getRequestId())).hasSize(5);
        assertThat(jobs.view(id,member.getLoginId(),null).recommendations()).hasSize(5);
        service.enqueue(id,member.getLoginId(),null,1);
        assertThat(service.claim(id)).isNull();
    }
    @Test void specifiedRegionProducesOneCandidateAndGuestHasNoMemberResultRows() {
        long before=results.count();UUID id=prepared("강릉",null);service.enqueue(id,null,guestHash,0);
        var work=service.claim(id);assertThat(work.candidates()).hasSize(1);
        service.complete(work,ranked(work));
        assertThat(results.count()).isEqualTo(before);
        var view=jobs.view(id,null,guestHash);
        assertThat(view.recommendations().getFirst().destination().name()).isEqualTo("강릉");
        assertThat(view.pending()).isFalse();assertThat(view.canRecommend()).isFalse();
    }
    @Test void guestJobStaysOpenAfterLoginInSameSessionOnly() {
        // 비회원으로 만든 추천은 같은 세션(같은 토큰)에서 로그인해도 이어서 볼 수 있고, 다른 토큰으로는 열 수 없다.
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var member=users.saveAndFlush(new User("guest_"+suffix,"unused-hash","이어보기"+suffix));
        UUID id=prepared("강릉",null);
        assertThat(jobs.view(id,member.getLoginId(),guestHash).status()).isNotBlank();
        byte[] otherHash=new byte[32]; otherHash[0]=1;
        assertThatThrownBy(()->jobs.view(id,member.getLoginId(),otherHash)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThatThrownBy(()->jobs.view(id,member.getLoginId(),null)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        // 저장 상세처럼 "열 수 있는지"만 볼 때는 예외 없이 빈 값을 받는다(예외가 나면 바깥 트랜잭션이 롤백 전용이 된다).
        assertThat(jobs.findOwned(id,member.getLoginId(),null,"RECOMMENDATION")).isEmpty();
        assertThat(jobs.findOwned(id,member.getLoginId(),guestHash,"RECOMMENDATION")).isPresent();
        assertThat(jobs.findOwned(id,"no_such_user",guestHash,"RECOMMENDATION")).isEmpty();
    }
    // 로그인하면 이 세션의 비회원 추천을 회원 추천 이력으로 옮긴다. 다른 세션·만료된 추천은 옮기지 않는다.
    @Test void loginAdoptsThisSessionsGuestRecommendationsIntoHistory() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var member=users.saveAndFlush(new User("adopt_"+suffix,"unused-hash","옮기기"+suffix));
        UUID done=prepared("강릉",null); service.enqueue(done,null,guestHash,0);
        var work=service.claim(done); service.complete(work,ranked(work));
        // 추천을 계산하는 중에 로그인한 경우: 옮긴 뒤 끝나도 회원 결과로 남아야 한다.
        UUID running=prepared("부산",null); service.enqueue(running,null,guestHash,0); var runningWork=service.claim(running);
        byte[] otherHash=new byte[32]; otherHash[0]=7;
        UUID other=jobs.create(new ConfirmedTravelConditions("KEYWORD","","경주",null,null,2,null,2,true,null,List.of(),""),null,otherHash);
        UUID expired=prepared("속초",null);
        em.flush(); jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSUTCDATETIME()) WHERE job_id=?",expired.toString()); em.clear();
        var requestedAt=jobRepository.findById(done).orElseThrow().getCreatedAt();

        assertThat(jobs.adoptGuestJobs(member.getLoginId(),guestHash)).isEqualTo(2);
        em.flush(); em.clear();
        var adopted=jobRepository.findById(done).orElseThrow();
        assertThat(adopted.getUserId()).isEqualTo(member.getId()); assertThat(adopted.getGuestTokenHash()).isNull();
        var history=requests.findTop100ByUserIdOrderByCreatedAtDescIdDesc(member.getId());
        assertThat(history).hasSize(2);
        var doneRequest=history.stream().filter(r->r.getId().equals(adopted.getRequestId())).findFirst().orElseThrow();
        assertThat(doneRequest.getCreatedAt()).isEqualTo(requestedAt);
        assertThat(results.findByRequestIdOrderByRank(adopted.getRequestId())).hasSize(1);
        // 옮긴 뒤에는 회원으로 열리고, 비회원 토큰으로는 더 이상 열리지 않는다.
        assertThat(jobs.view(done,member.getLoginId(),null).recommendations()).hasSize(1);
        assertThatThrownBy(()->jobs.view(done,null,guestHash)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        // 처리 중이던 추천은 끝날 때 회원 결과로 저장된다.
        service.complete(runningWork,ranked(runningWork)); em.flush();
        var runningJob=jobRepository.findById(running).orElseThrow();
        assertThat(runningJob.getStatus()).isEqualTo("COMPLETED");
        assertThat(results.findByRequestIdOrderByRank(runningJob.getRequestId())).hasSize(1);
        // 다른 세션의 추천과 만료된 추천은 그대로 비회원 작업이다.
        assertThat(jobRepository.findById(other).orElseThrow().getUserId()).isNull();
        assertThat(jobRepository.findById(expired).orElseThrow().getUserId()).isNull();
        // 같은 세션으로 다시 로그인해도 더 옮길 것이 없다.
        assertThat(jobs.adoptGuestJobs(member.getLoginId(),guestHash)).isZero();
    }
    @Test void retryIsExplicitBoundedAndOldSubmissionDoesNotRetryFailure() {
        UUID id=prepared("부산",null);
        for(int attempt=0;attempt<3;attempt++) {
            service.enqueue(id,null,guestHash,attempt);var work=service.claim(id);
            service.fail(work,"AI_TIMEOUT","추천 응답 시간이 초과됐어요.");
            service.enqueue(id,null,guestHash,attempt); // 실패 전 버튼의 재전송
            assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo("FAILED");
        }
        assertThat(jobs.view(id,null,guestHash).canRecommend()).isFalse();
        assertThatThrownBy(()->service.enqueue(id,null,guestHash,3)).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }
    @Test void staleResponseCannotOverwriteNewAttemptAndInactivePlaceIsRejected() {
        UUID id=prepared("부산",null);service.enqueue(id,null,guestHash,0);var old=service.claim(id);
        service.fail(old,"AI_TIMEOUT","시간 초과");service.enqueue(id,null,guestHash,1);var current=service.claim(id);
        service.complete(old,ranked(old));assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo("RANKING_CANDIDATES");
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_name=N'부산'");
        em.flush();em.clear();service.complete(current,ranked(current));
        assertThat(jobRepository.findById(id).orElseThrow().getErrorCode()).isEqualTo("CANDIDATES_CHANGED");
    }
    @Test void wrongCandidateCannotBePersisted() {
        UUID id=prepared("경주",null);service.enqueue(id,null,guestHash,0);var work=service.claim(id);
        long before=results.count();
        var fake=new DestinationCandidate(999999L,"없는 지역","", "","https://example.com",null);
        assertThatThrownBy(()->service.complete(work,List.of(new RankedDestination(1,fake,"위조 후보"))))
            .isInstanceOf(RecommendationException.class);
        assertThat(results.count()).isEqualTo(before);
        assertThat(jobRepository.findById(id).orElseThrow().getStatus()).isEqualTo("RANKING_CANDIDATES");
    }
    @Test void rankingEndpointChecksCsrfOwnershipAndRendersCompletedResult() throws Exception {
        var session=new MockHttpSession();
        var c=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,null,null,null,null,null,List.of(),"");
        session.setAttribute("confirmedTravelConditions",c);session.setAttribute("travelConfirmationToken","confirm");
        String path=mvc.perform(post("/travel/recommendations").session(session).with(csrf()).param("confirmationToken","confirm"))
            .andReturn().getResponse().getHeader("Location");
        UUID id=UUID.fromString(path.substring(path.lastIndexOf('/')+1));jobs.prepare(id);
        mvc.perform(post(path+"/recommend").session(session).param("attempt","0")).andExpect(status().isForbidden());
        mvc.perform(post(path+"/recommend").session(new MockHttpSession()).with(csrf()).param("attempt","0")).andExpect(status().isNotFound());
        mvc.perform(post(path+"/recommend").session(session).with(csrf()).param("attempt","0")).andExpect(status().isAccepted());
        var work=service.claim(id);service.complete(work,ranked(work));
        mvc.perform(get(path).session(session)).andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("1순위 · 강릉")));
        mvc.perform(get(path+"/status").session(session)).andExpect(jsonPath("$.status").value("COMPLETED"))
            .andExpect(jsonPath("$.recommendations.length()").value(1));
    }
    @Test void expiredAndSlowResponsesCannotComplete() {
        UUID id=prepared("강릉",null);service.enqueue(id,null,guestHash,0);var work=service.claim(id);
        em.flush();
        jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSUTCDATETIME()) WHERE job_id=?",id.toString());
        em.clear();service.complete(work,ranked(work));
        assertThat(jobRepository.findById(id).orElseThrow().getErrorCode()).isEqualTo("RECOMMENDATION_TIMEOUT");
    }
}
