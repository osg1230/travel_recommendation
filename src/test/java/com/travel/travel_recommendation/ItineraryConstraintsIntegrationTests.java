package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import com.travel.travel_recommendation.service.ItineraryJobService.ReplaceReason;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 필수·선호 조건 구분(SFR-02-07)을 일정 입력 화면부터 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class ItineraryConstraintsIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    // 장소 수집(refresh)이 실제 관광공사·GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 빈 목록을 돌려준다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    private MockHttpSession session;
    private byte[] hash;
    private UUID parent;
    private long destination;

    @BeforeEach void setup() throws Exception {
        // 브라우저 확인 등으로 쌓인 카카오 장소 캐시가 후보 수를 바꾸지 않도록 테스트 트랜잭션 안에서 만료시킨다(롤백됨).
        ExternalPlaceCache.expireExisting(jdbc);
        // 강릉 기본 관광지 2곳에 테스트 관광지 3곳을 더해 5곳으로 확인한다(롤백됨).
        String[][] extra={{"하슬라 테스트 정원","2001"},{"해변 테스트 공원","2002"},{"허브 테스트 실내 체험관","2003"}};
        for(var place:extra) jdbc.update("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('ATTRACTION','SYSTEM',?,?,N'강원특별자치도 강릉시',?,N'테스트',N'테스트 관광지',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""",
            "TEST_CONSTRAINT_"+place[1],place[0],"강원특별자치도 강릉시 경포로 "+place[1]);
        session=new MockHttpSession(); String token=UUID.randomUUID().toString();
        session.setAttribute("recommendationGuestToken",token);
        hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,2,null,2,true,false,List.of(),"");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidates=recommendations.view(parent,null,hash).candidates();
        var gangneung=candidates.stream().filter(c->c.name().equals("강릉")).findFirst().orElse(candidates.getFirst());
        destination=gangneung.placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,gangneung,"추천"))));
        em.flush();
        when(maps.locate(anyString())).thenAnswer(call->{
            String address=call.getArgument(0);
            return new KakaoMapClient.Location(address,128.8+(Math.abs(address.hashCode()%1000))/100000.0,37.7,Instant.now());
        });
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
    }
    private String formUrl() { return "/travel/recommendations/"+parent+"/destinations/"+destination+"/schedule"; }
    private String token() throws Exception {
        var html=mvc.perform(get(formUrl()).session(session)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        var m=Pattern.compile("name=\"submissionToken\" value=\"([^\"]+)\"").matcher(html);
        assertThat(m.find()).isTrue(); return m.group(1);
    }
    private long placeId(String name) {
        return schedules.attractionPool(parent,destination,null,hash).stream().filter(p->p.name().equals(name)).findFirst().orElseThrow().placeId();
    }
    private UUID jobFrom(String location) { return UUID.fromString(location.substring(location.lastIndexOf('/')+1)); }
    private void finish(UUID id) { var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush(); }

    @Test void requiredPlaceAndRequiredDailyCountShapeTheDraft() throws Exception {
        long herb=placeId("허브 테스트 실내 체험관");
        var location=mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","19:00").param("transport","CAR").param("visitMinutes","60")
                .param("required",String.valueOf(herb)).param("dailyVisits","2").param("dailyMode","REQUIRED"))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        var id=jobFrom(location);
        var input=schedules.view(id,null,hash).input();
        assertThat(input.constraints().requiredPlaceIds()).containsExactly(herb);
        assertThat(input.places().attractions()).hasSize(4).first().extracting(PlaceCandidate::placeId).isEqualTo(herb);
        finish(id);
        var view=schedules.view(id,null,hash);
        assertThat(view.result().days()).allSatisfy(d->assertThat(d.items().stream().filter(i->i.type().equals("VISIT"))).hasSize(2));
        var report=ItineraryConditionReport.evaluate(view.input(),view.result());
        assertThat(report.complete()).isTrue();
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("필수 방문 장소"); assertThat(c.status()).isEqualTo(ItineraryConditionReport.Status.MET); });
        // 필수 장소는 수정안에서 교체할 수 없다.
        assertThatThrownBy(()->schedules.revise(id,null,hash,List.of(herb),ReplaceReason.TASTE,LocalTime.of(9,0),LocalTime.of(19,0),60))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("필수 방문 장소");
    }

    @Test void startDateChosenOnScheduleFormFillsTripDates() throws Exception {
        // 추천 때 출발일을 정하지 않았다면 일정 만들기에서 받은 출발일로 날짜를 채워 저장할 수 있게 한다.
        var start=LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(10);
        var location=mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90")
                .param("startDate",start.toString()))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        var id=jobFrom(location);
        var conditions=schedules.view(id,null,hash).input().conditions();
        assertThat(conditions.startDate()).isEqualTo(start);
        assertThat(conditions.endDate()).isEqualTo(start.plusDays(1));
        finish(id);
        assertThat(schedules.view(id,null,hash).result().days()).extracting(d->d.date()).containsExactly(start,start.plusDays(1));

        // 지난 날짜는 일정을 만들지 않고 입력 화면에서 이유를 보여 준다.
        long before=jobs.count();
        mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90")
                .param("startDate",start.minusDays(20).toString()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location",formUrl()));
        assertThat(jobs.count()).isEqualTo(before);
        mvc.perform(get(formUrl()).session(session)).andExpect(content().string(containsString("출발일은 오늘이나 그 이후 날짜로")));
    }

    @Test void contradictoryRequiredConditionsReturnToFormWithReasonsAndValues() throws Exception {
        long a=placeId("하슬라 테스트 정원"), b=placeId("해변 테스트 공원"), c=placeId("허브 테스트 실내 체험관");
        long before=jobs.count();
        mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90")
                .param("required",String.valueOf(a),String.valueOf(b),String.valueOf(c)).param("dailyVisits","1").param("dailyMode","REQUIRED"))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location",formUrl()));
        assertThat(jobs.count()).isEqualTo(before);
        var html=mvc.perform(get(formUrl()).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("아래 내용 때문에 일정을 만들지 않았어요")))
            .andExpect(content().string(containsString("필수 방문 장소 3곳을 하루 1곳씩 2일")))
            .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        if(Boolean.getBoolean("travel.ui.capture")) {
            var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve("itinerary-form-conflict.html"),html);
        }
        // 입력했던 필수 장소 선택이 그대로 남아 있다.
        assertThat(html).containsPattern("value=\""+a+"\" checked");
        // 한 번 보여 준 충돌 안내는 다시 열면 사라진다.
        mvc.perform(get(formUrl()).session(session)).andExpect(content().string(org.hamcrest.Matchers.not(containsString("아래 내용 때문에"))));
    }

    @Test void preferredDailyCountDoesNotBlockAndForgedValuesAreRejected() throws Exception {
        var location=mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","13:00").param("transport","CAR").param("visitMinutes","90")
                .param("dailyVisits","4").param("dailyMode","PREFERRED"))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        // 09:00~13:00·체류 90분이면 하루 약 1곳이라 선호 4곳은 2곳(2일 × 1곳)으로 줄여 반영한다.
        assertThat(schedules.view(jobFrom(location),null,hash).input().places().attractions()).hasSize(2);
        mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90").param("required","999999"))
            .andExpect(status().isBadRequest());
        mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token())
                .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90").param("dailyMode","SOMETIMES"))
            .andExpect(status().isBadRequest());
    }
}
