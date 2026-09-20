package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class ItineraryIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    @MockitoBean TourApiClient tour;
    @Autowired PlaceOperatingService operating;
    @Autowired PlaceRepository placeRepository;
    private MockHttpSession session;
    private byte[] hash;
    private UUID parent;
    private long destination;
    @BeforeEach void setup() throws Exception {
        session=new MockHttpSession(); String token=UUID.randomUUID().toString();
        session.setAttribute("recommendationGuestToken",token);
        hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,2,null,2,true,false,List.of(),"");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidates=recommendations.view(parent,null,hash).candidates(); destination=candidates.getFirst().placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidates.getFirst(),"추천"))));
        em.flush();
        when(maps.locate(anyString())).thenAnswer(call->{
            String address=call.getArgument(0);
            return new KakaoMapClient.Location(address,128.8+(Math.abs(address.hashCode()%1000))/100000.0,37.7,Instant.now());
        });
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
    }
    private UUID create() { return schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90); }
    private String formUrl() { return "/travel/recommendations/"+parent+"/destinations/"+destination+"/schedule"; }
    private String resultUrl(UUID id) { return "/travel/recommendations/itineraries/"+id; }

    @Test void operatingInfoPersistsReachesScheduleAndDetectsLaterChanges() throws Exception {
        var candidate=schedules.options(parent,destination,null,hash).places().attractions().getFirst();
        when(tour.search(candidate.name())).thenReturn(List.of(new TourApiClient.SearchPlace("123","14",candidate.name(),candidate.address(),128.88,37.78)));
        var info=new PlaceOperatingInfo("123","14","매표 09:00~17:00 관람 09:00~18:00","연중무휴(명절 부분 휴관)","","",Instant.now());
        when(tour.findOperatingInfo("123","14")).thenReturn(Optional.of(info));
        assertThat(operating.refresh(parent,destination,candidate.placeId(),null,hash)).isTrue();
        em.flush(); em.clear();
        assertThat(schedules.options(parent,destination,null,hash).places().attractions().getFirst().operatingInfo()).isEqualTo(info);
        var id=create(); var work=schedules.claim(id); var result=schedules.generate(work);
        assertThat(result.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("VISIT")).map(i->i.note()))
            .anyMatch(note->note.contains("명절 부분 휴관") && note.contains("확인 필요"));
        schedules.complete(work,result);
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("명절 부분 휴관")));
        var second=create(); var secondWork=schedules.claim(second); var secondResult=schedules.generate(secondWork);
        placeRepository.findById(candidate.placeId()).orElseThrow().updateDetails("{}"); em.flush();
        schedules.complete(secondWork,secondResult);
        assertThat(schedules.view(second,null,hash).status()).isEqualTo("FAILED");
        assertThatThrownBy(()->operating.refresh(parent,destination,candidate.placeId(),null,new byte[32]))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(tour,times(1)).search(candidate.name());
    }

    @Test void createsIsolatedQueueCompletesAndRendersDraft() throws Exception {
        var id=create();
        assertThat(schedules.queued()).contains(id); assertThat(recommendations.queued()).doesNotContain(id);
        recommendations.prepare(id); assertThat(schedules.view(id,null,hash).status()).isEqualTo("QUEUED");
        var work=schedules.claim(id); assertThat(schedules.claim(id)).isNull();
        var result=schedules.generate(work); schedules.complete(work,result); schedules.complete(work,result); em.flush();
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
        assertThat(schedules.view(id,null,hash).result().days()).hasSize(2);
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("일정 초안"))).andExpect(content().string(containsString("2일차")))
            .andExpect(content().string(containsString("확인이 필요해요")))
            .andDo(response->{ if(Boolean.getBoolean("travel.ui.capture")) {
                var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
                java.nio.file.Files.writeString(folder.resolve("itinerary.html"),response.getResponse().getContentAsString(StandardCharsets.UTF_8));
            }});
        mvc.perform(get(resultUrl(id)+"/status").session(session)).andExpect(status().isOk()).andExpect(jsonPath("$.pending").value(false));
        mvc.perform(get("/travel/recommendations/"+id).session(session)).andExpect(status().isNotFound());
    }
    @Test void formCsrfAndDuplicateSubmission() throws Exception {
        var form=mvc.perform(get(formUrl()).session(session)).andExpect(status().isOk()).andReturn();
        String token=(String)form.getModelAndView().getModel().get("submissionToken");
        if(Boolean.getBoolean("travel.ui.capture")) {
            var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve("itinerary-form.html"),form.getResponse().getContentAsString(StandardCharsets.UTF_8));
        }
        mvc.perform(post(formUrl()).session(session).param("submissionToken",token).param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90")).andExpect(status().isForbidden());
        String location=null;
        for(int i=0;i<2;i++) {
            var response=mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token)
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90"))
                .andExpect(status().isSeeOther()).andReturn();
            if(location==null) location=response.getResponse().getHeader("Location");
            else assertThat(response.getResponse().getHeader("Location")).isEqualTo(location);
        }
        verifyNoInteractions(maps);
    }
    @Test void refusesOtherGuestWrongTypeAndExpiredJob() throws Exception {
        var id=create();
        mvc.perform(get(resultUrl(id)).session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(get(resultUrl(parent)).session(session)).andExpect(status().isNotFound());
        em.flush(); jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSUTCDATETIME()) WHERE job_id=?",id.toString()); em.clear();
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isNotFound());
    }
    @Test void changedPlaceCannotComplete() {
        var id=create(); var work=schedules.claim(id); var result=schedules.generate(work);
        em.flush(); jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_id=?",work.input().places().attractions().getFirst().placeId()); em.clear();
        schedules.complete(work,result); assertThat(schedules.view(id,null,hash).status()).isEqualTo("FAILED");
        assertThat(schedules.view(id,null,hash).result()).isNull();
    }
    @Test void lateResultCannotComplete() {
        var id=create(); var work=schedules.claim(id); var result=schedules.generate(work);
        em.flush(); jdbc.update("UPDATE dbo.ai_jobs SET started_at=DATEADD(minute,-3,SYSUTCDATETIME()) WHERE job_id=?",id.toString()); em.clear();
        schedules.complete(work,result);
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("FAILED");
        assertThat(schedules.view(id,null,hash).result()).isNull();
    }
    @Test void failureAndRestartAreTerminal() {
        var id=create(); var work=schedules.claim(id);
        schedules.fail(work,"MAP_AUTH","지도 연결 설정을 확인해 주세요.");
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("FAILED");
        assertThat(schedules.claim(id)).isNull();
        var second=create(); schedules.claim(second); recommendations.recover(); em.clear();
        assertThat(schedules.view(second,null,hash).status()).isEqualTo("FAILED");
    }
}
