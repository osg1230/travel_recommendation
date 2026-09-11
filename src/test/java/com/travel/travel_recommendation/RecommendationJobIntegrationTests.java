package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static com.travel.travel_recommendation.controller.TravelConditionReviewController.*;

@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class RecommendationJobIntegrationTests {
    @Autowired RecommendationJobService service;
    @Autowired DestinationCandidateService destinations;
    @Autowired AiJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired RecommendationRequestRepository requests;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    private ConfirmedTravelConditions conditions(String region) {
        return new ConfirmedTravelConditions("KEYWORD","",region,null,null,3,new BigDecimal("600000"),2,true,false,List.of(),"");
    }
    private MockHttpSession session() {
        var session=new MockHttpSession();
        session.setAttribute(CONFIRMED_KEY,conditions("강릉"));
        session.setAttribute(CONFIRMATION_TOKEN,"confirmed");
        return session;
    }
    private String create(MockHttpSession session) throws Exception {
        return mvc.perform(post("/travel/recommendations").session(session).with(csrf()).param("confirmationToken","confirmed"))
            .andExpect(status().isAccepted()).andReturn().getResponse().getHeader("Location");
    }
    @Test void guestFlowCreatesOneJobAndShowsVerifiedCandidateWithoutMemberHistory() throws Exception {
        long before=requests.count();
        var session=session(); String location=create(session);
        assertThat(create(session)).isEqualTo(location);
        UUID id=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertThat(jobs.findById(id).orElseThrow().getStatus()).isEqualTo("QUEUED");
        assertThat(jobs.findById(id).orElseThrow().getGuestTokenHash()).hasSize(32);
        service.prepare(id);
        assertThat(requests.count()).isEqualTo(before);
        mvc.perform(get(location).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("강릉")))
            .andExpect(content().string(containsString("https://visitgangneung.net/")))
            .andExpect(content().string(containsString("실시간 조회 아님")));
        mvc.perform(get(location+"/status").session(session)).andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("CANDIDATES_READY"))
            .andExpect(jsonPath("$.pending").value(false))
            .andExpect(jsonPath("$.candidates.length()").value(1));
    }
    @Test void jobIdDoesNotGrantAccessToAnotherGuest() throws Exception {
        String location=create(session());
        mvc.perform(get(location).session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(get(location+"/status").session(new MockHttpSession())).andExpect(status().isNotFound());
    }
    @Test void rejectsMissingCsrfUnconfirmedAndStaleSubmission() throws Exception {
        mvc.perform(post("/travel/recommendations").session(session()).param("confirmationToken","confirmed"))
            .andExpect(status().isForbidden());
        mvc.perform(post("/travel/recommendations").with(csrf()).param("confirmationToken","confirmed"))
            .andExpect(status().isConflict());
        mvc.perform(post("/travel/recommendations").session(session()).with(csrf()).param("confirmationToken","old"))
            .andExpect(status().isConflict());
    }
    @Test void nonJavascriptFormRedirectsToGet() throws Exception {
        mvc.perform(post("/travel/recommendations").session(session()).with(csrf()).param("confirmationToken","confirmed")
            .header("Accept","text/html")).andExpect(status().isSeeOther());
    }
    @Test void memberSnapshotAndOwnershipAreChecked() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        User member=users.saveAndFlush(new User("job_"+suffix,"unused-test-hash","작업"+suffix));
        User other=users.saveAndFlush(new User("other_"+suffix,"unused-test-hash","다른"+suffix));
        UUID id=service.create(conditions("부산"),member.getLoginId(),null);
        var job=jobs.findById(id).orElseThrow();
        assertThat(job.getGuestTokenHash()).isNull();
        var request=requests.findById(job.getRequestId()).orElseThrow();
        assertThat(request.getUserId()).isEqualTo(member.getId());
        assertThat(request.getRegion()).isEqualTo("부산");
        var json=mapper.readTree(request.getConditionsJson());
        assertThat(json.get("party_size").asInt()).isEqualTo(2);
        assertThat(json.has("region")).isFalse();
        service.prepare(id);
        assertThat(service.view(id,member.getLoginId(),null).candidates()).hasSize(1);
        assertThatThrownBy(()->service.view(id,other.getLoginId(),null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.view(id,null,new byte[32])).isInstanceOf(ResponseStatusException.class);
    }
    @Test void restrictedMemberCannotReadOrExecuteExistingJob() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        User member=users.saveAndFlush(new User("blocked_"+suffix,"unused-test-hash","제한"+suffix));
        UUID id=service.create(conditions("부산"),member.getLoginId(),null);
        jdbc.update("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=?",member.getId());
        entityManager.clear();
        service.prepare(id);
        assertThat(jobs.findById(id).orElseThrow().getErrorCode()).isEqualTo("ACCOUNT_UNAVAILABLE");
        assertThatThrownBy(()->service.view(id,member.getLoginId(),null))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
    }
    @Test void unmatchedRegionIsExplicitFailureAndRetryCreatesNewJob() throws Exception {
        var session=session();session.setAttribute(CONFIRMED_KEY,conditions("제주"));
        String location=create(session);UUID id=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        service.prepare(id);
        mvc.perform(get(location+"/status").session(session)).andExpect(jsonPath("$.errorCode").value("NO_DESTINATIONS"));
        assertThat(create(session)).isEqualTo(location);
        String retry=mvc.perform(post("/travel/recommendations").session(session).with(csrf())
            .param("confirmationToken","confirmed").param("retry","true")).andExpect(status().isAccepted())
            .andReturn().getResponse().getHeader("Location");
        assertThat(retry).isNotEqualTo(location);
    }
    @Test void regionMatchingDoesNotInventNearbyPlacesOrTreatSubstringsAsRegions() {
        assertThat(destinations.find("")).hasSize(3);
        assertThat(destinations.find("강원특별자치도 강릉시")).extracting(DestinationCandidate::name).containsExactly("강릉");
        assertThat(destinations.find("경북")).extracting(DestinationCandidate::name).containsExactly("경주");
        assertThat(destinations.find("부산 아닌 서울")).isEmpty();
        assertThat(destinations.find("강릉 부산")).isEmpty();
    }
    @Test void inactiveAndUnverifiedSourcesAreExcluded() {
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_name=N'강릉'");
        jdbc.update("UPDATE dbo.places SET source_url='javascript:alert(1)' WHERE place_name=N'부산'");
        assertThat(destinations.find("")).extracting(DestinationCandidate::name).containsExactly("경주");
    }
    @Test void expiredGuestsAreDeniedThenDeletedButMemberRequestsRemain() {
        UUID id=service.create(conditions(""),null,new byte[32]);
        jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSUTCDATETIME()) WHERE job_id=?",id.toString());
        // JDBC로 변경한 값을 다시 읽도록 영속성 컨텍스트를 비운다.
        entityManager.clear();
        assertThatThrownBy(()->service.view(id,null,new byte[32])).isInstanceOf(ResponseStatusException.class);
        service.cleanup();
        assertThat(jobs.findById(id)).isEmpty();
    }
    @Autowired jakarta.persistence.EntityManager entityManager;
    @Test void restartRecoveryAndQueueTimeoutNeverReportSuccess() {
        UUID id=service.create(conditions("강릉"),null,new byte[32]);
        jdbc.update("UPDATE dbo.ai_jobs SET status='SEARCHING_CANDIDATES' WHERE job_id=?",id.toString());
        entityManager.clear();service.recover();entityManager.clear();
        assertThat(jobs.findById(id).orElseThrow().getErrorCode()).isEqualTo("SERVER_RESTART");
        UUID waiting=service.create(conditions("강릉"),null,new byte[32]);
        jdbc.update("UPDATE dbo.ai_jobs SET created_at=DATEADD(minute,-2,SYSUTCDATETIME()) WHERE job_id=?",waiting.toString());
        entityManager.clear();service.prepare(waiting);
        assertThat(jobs.findById(waiting).orElseThrow().getErrorCode()).isEqualTo("TIMEOUT");
    }
}
