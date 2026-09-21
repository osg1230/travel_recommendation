package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.PlaceRef;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import com.travel.travel_recommendation.service.ItineraryJobService.ReplaceReason;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 일정 초안의 장소 유지·부분 교체 수정안(SFR-04-07·08-04)을 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class ItineraryRevisionIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    @MockitoBean TourApiClient tour;
    private MockHttpSession session;
    private byte[] hash;
    private UUID parent;
    private long destination;

    @BeforeEach void setup() throws Exception {
        // 강릉 기본 관광지는 2곳뿐이라 교체 후보를 확인할 수 있게 테스트 관광지를 추가한다(롤백됨).
        // 이름순 정렬에서 뒤에 오도록 이름을 정해, 2일 일정에는 앞의 4곳이 쓰이고 마지막 1곳이 교체 후보로 남는다.
        String[][] extra={{"하슬라 테스트 정원","1001"},{"해변 테스트 공원","1002"},{"허브 테스트 실내 체험관","1003"}};
        for(var place:extra) jdbc.update("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('ATTRACTION','SYSTEM',?,?,N'강원특별자치도 강릉시',?,N'테스트',N'테스트 관광지',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSUTCDATETIME()))""",
            "TEST_REVISION_"+place[1],place[0],"강원특별자치도 강릉시 경포로 "+place[1]);
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
    private UUID completedDraft() {
        var id=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        finish(id); return id;
    }
    private void finish(UUID id) {
        var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush();
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
    }
    private List<Long> attractionIds(UUID id) {
        return schedules.view(id,null,hash).input().places().attractions().stream().map(PlaceCandidate::placeId).toList();
    }

    @Test void replacesOnlyChosenPlaceKeepsOthersAndLeavesOriginalUntouched() throws Exception {
        var original=completedDraft();
        var before=schedules.view(original,null,hash);
        var used=before.input().places().attractions();
        assertThat(used).hasSize(4);
        var target=used.getFirst();
        var revised=schedules.revise(original,null,hash,List.of(target.placeId()),ReplaceReason.INDOOR,LocalTime.of(10,0),LocalTime.of(19,0),60);

        var revision=schedules.view(revised,null,hash).input().revision();
        assertThat(revision.sourceJobId()).isEqualTo(original);
        assertThat(revision.replaced()).extracting(PlaceRef::placeId).containsExactly(target.placeId());
        assertThat(revision.added()).extracting(PlaceRef::name).containsExactly("허브 테스트 실내 체험관");
        assertThat(revision.kept()).extracting(PlaceRef::placeId).containsExactlyElementsOf(used.subList(1,4).stream().map(PlaceCandidate::placeId).toList());
        assertThat(revision.reason()).isEqualTo("INDOOR");
        assertThat(attractionIds(revised)).doesNotContain(target.placeId()).hasSize(4);
        assertThat(schedules.view(revised,null,hash).input().start()).isEqualTo(LocalTime.of(10,0));

        // 원본 초안은 입력·결과 모두 그대로다.
        var after=schedules.view(original,null,hash);
        assertThat(after.input()).isEqualTo(before.input()); assertThat(after.result()).isEqualTo(before.result());

        finish(revised);
        var view=schedules.view(revised,null,hash);
        var report=ItineraryConditionReport.evaluate(view.input(),view.result());
        assertThat(report.complete()).isTrue();
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("유지한 관광지"); assertThat(c.status()).isEqualTo(ItineraryConditionReport.Status.MET); });
        mvc.perform(get("/travel/recommendations/itineraries/"+revised).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("일정 수정안")))
            .andExpect(content().string(containsString("원래 초안 보기")))
            .andExpect(content().string(containsString("허브 테스트 실내 체험관")));
    }

    @Test void timeOnlyRevisionKeepsEveryPlace() {
        var original=completedDraft();
        var revised=schedules.revise(original,null,hash,List.of(),null,LocalTime.of(8,0),LocalTime.of(20,0),120);
        assertThat(attractionIds(revised)).containsExactlyElementsOf(attractionIds(original));
        assertThat(schedules.view(revised,null,hash).input().revision().replaced()).isEmpty();
        finish(revised);
    }

    @Test void invalidRequestsDoNotCreateJobs() {
        var original=completedDraft();
        var ids=attractionIds(original);
        long count=jobs.count();
        assertThatThrownBy(()->schedules.revise(original,null,hash,ids,ReplaceReason.TASTE,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("유지할 관광지");
        assertThatThrownBy(()->schedules.revise(original,null,hash,List.of(ids.getFirst()),null,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("교체 이유");
        assertThatThrownBy(()->schedules.revise(original,null,hash,List.of(destination),ReplaceReason.TASTE,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이 초안에 있는 관광지");
        // 남은 후보는 1곳이라 2곳을 바꿀 수 없다.
        assertThatThrownBy(()->schedules.revise(original,null,hash,ids.subList(0,2),ReplaceReason.TASTE,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("후보가 부족");
        assertThatThrownBy(()->schedules.revise(original,null,hash,List.of(),null,LocalTime.of(9,0),LocalTime.of(12,0),90))
            .isInstanceOf(RecommendationException.class);
        assertThatThrownBy(()->schedules.revise(original,null,new byte[32],List.of(),null,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class);
        var pending=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        assertThatThrownBy(()->schedules.revise(pending,null,hash,List.of(),null,LocalTime.of(9,0),LocalTime.of(18,0),90))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("완성된 일정 초안");
        assertThat(jobs.count()).isEqualTo(count+1);
    }

    @Test void formPostRedirectsAndFailureOffersOriginal() throws Exception {
        var original=completedDraft();
        var ids=attractionIds(original);
        var url="/travel/recommendations/itineraries/"+original+"/revisions";
        mvc.perform(post(url).session(session).param("start","09:00").param("end","18:00").param("visitMinutes","90"))
            .andExpect(status().isForbidden());
        mvc.perform(post(url).session(session).with(csrf()).param("replace",ids.getFirst().toString()).param("reason","TASTE")
                .param("start","09:00").param("end","18:00").param("visitMinutes","90"))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location",containsString("/travel/recommendations/itineraries/")));
        mvc.perform(post(url).session(session).with(csrf()).param("replace",ids.getFirst().toString())
                .param("start","09:00").param("end","18:00").param("visitMinutes","90"))
            .andExpect(status().isBadRequest()).andExpect(content().string(containsString("원래 일정 초안으로 돌아가기")));
        mvc.perform(get("/travel/recommendations/itineraries/"+original).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("장소 바꿔서 수정안 만들기")));
    }
}
