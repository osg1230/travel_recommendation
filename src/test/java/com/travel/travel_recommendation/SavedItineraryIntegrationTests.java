package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
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
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 회원 일정 저장·조회·이름 수정·삭제를 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class SavedItineraryIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired SavedItineraryService saved;
    @Autowired AiJobRepository jobs;
    @Autowired ItineraryRepository itineraries;
    @Autowired ItineraryItemRepository items;
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
    private UUID itineraryJob;

    @BeforeEach void setup() {
        owner=member();
        when(maps.locate(anyString())).thenAnswer(call->new KakaoMapClient.Location(call.getArgument(0),128.9,37.7,Instant.now()));
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
        itineraryJob=completedDraft(owner);
    }
    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        return users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
    }
    private UUID completedDraft(User user) {
        var start=LocalDate.now().plusDays(7);
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(),"");
        var parent=recommendations.create(conditions,user.getLoginId(),null);
        recommendations.prepare(parent);
        var candidates=recommendations.view(parent,user.getLoginId(),null).candidates();
        long destination=candidates.getFirst().placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(
            mapper.writeValueAsString(List.of(new RankedDestination(1,candidates.getFirst(),"추천"))));
        em.flush();
        var id=schedules.create(parent,destination,user.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),
            KakaoMapClient.Transport.CAR,90);
        var work=schedules.claim(id);
        schedules.complete(work,schedules.generate(work));
        em.flush();
        return id;
    }

    @Test void savesDraftOnceAndKeepsDaysAndItems() {
        var result=schedules.view(itineraryJob,owner.getLoginId(),null).result();
        var first=saved.save(itineraryJob,owner.getLoginId());
        assertThat(first.created()).isTrue();
        em.flush(); em.clear();
        // 같은 초안을 다시 저장해도 일정이 늘지 않는다.
        var again=saved.save(itineraryJob,owner.getLoginId());
        assertThat(again.created()).isFalse();
        assertThat(again.id()).isEqualTo(first.id());
        assertThat(itineraries.findByUserIdOrderByStartDateDescIdDesc(owner.getId())).hasSize(1);
        var detail=saved.detail(first.id(),owner.getLoginId());
        assertThat(detail.days()).hasSize(result.days().size());
        assertThat(detail.days().getFirst().items()).hasSize(result.days().getFirst().items().size());
        assertThat(detail.days().getFirst().items().getFirst().name())
            .isEqualTo(result.days().getFirst().items().getFirst().name());
        assertThat(detail.summary().startDate()).isEqualTo(result.days().getFirst().date());
        assertThat(detail.summary().endDate()).isEqualTo(result.days().getLast().date());
        assertThat(detail.transportName()).isEqualTo("자동차");
    }

    @Test void savedDraftLinksToSavedItineraryInsteadOfSavingAgain() throws Exception {
        String url="/travel/recommendations/itineraries/"+itineraryJob;
        mvc.perform(get(url).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("이 일정 저장하기")));
        var first=saved.save(itineraryJob,owner.getLoginId());
        em.flush();
        // 이미 저장한 초안은 저장 버튼 대신 저장한 일정으로 가는 링크 버튼('저장한 ○○ 보기')을 보여 준다.
        mvc.perform(get(url).with(user(owner.getLoginId())))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("/my/itineraries/"+first.id())))
            .andExpect(content().string(containsString(" 보기</a>")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("이 일정 저장하기"))));
    }

    @Test void otherMemberCannotReadRenameOrDeleteSavedItinerary() throws Exception {
        long id=saved.save(itineraryJob,owner.getLoginId()).id();
        em.flush(); em.clear();
        var other=member();
        mvc.perform(get("/my/itineraries/"+id).with(user(other.getLoginId())))
            .andExpect(status().isNotFound()).andExpect(content().string(containsString("찾을 수 없어요")));
        mvc.perform(post("/my/itineraries/"+id+"/delete").with(user(other.getLoginId())).with(csrf()))
            .andExpect(status().isNotFound());
        // 이름 변경도 다른 회원이면 찾을 수 없다고 하고, 제목은 그대로 둔다.
        String title=itineraries.findById(id).orElseThrow().getTitle();
        mvc.perform(post("/my/itineraries/"+id+"/title").with(user(other.getLoginId())).with(csrf()).param("title","남의 일정"))
            .andExpect(status().isNotFound());
        em.clear();
        assertThat(itineraries.findById(id).orElseThrow().getTitle()).isEqualTo(title);
        mvc.perform(get("/my/itineraries").with(user(other.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("아직 저장한 일정이 없어요")));
        assertThat(itineraries.findById(id)).isPresent();
    }

    @Test void renamesAndDeletesWithItems() throws Exception {
        long id=saved.save(itineraryJob,owner.getLoginId()).id();
        em.flush(); em.clear();
        mvc.perform(post("/my/itineraries/"+id+"/title").with(user(owner.getLoginId())).with(csrf())
            .param("title"," 가을 강릉 여행 ")).andExpect(redirectedUrl("/my/itineraries/"+id));
        em.clear();
        assertThat(itineraries.findById(id).orElseThrow().getTitle()).isEqualTo("가을 강릉 여행");
        mvc.perform(post("/my/itineraries/"+id+"/title").with(user(owner.getLoginId())).with(csrf()).param("title","  "))
            .andExpect(status().isBadRequest());
        // 경계값: 150자는 저장하고 151자는 거절한다(점검 보고 29번).
        mvc.perform(post("/my/itineraries/"+id+"/title").with(user(owner.getLoginId())).with(csrf()).param("title","가".repeat(151)))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/my/itineraries/"+id+"/title").with(user(owner.getLoginId())).with(csrf()).param("title","가".repeat(150)))
            .andExpect(redirectedUrl("/my/itineraries/"+id));
        em.clear();
        assertThat(itineraries.findById(id).orElseThrow().getTitle()).hasSize(150);
        mvc.perform(post("/my/itineraries/"+id+"/delete").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/itineraries"));
        em.clear();
        assertThat(itineraries.findById(id)).isEmpty();
        assertThat(items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(id)).isEmpty();
    }

    @Test void draftWithoutTravelDatesCannotBeSaved() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,2,null,2,true,false,List.of(),"");
        var parent=recommendations.create(conditions,owner.getLoginId(),null);
        recommendations.prepare(parent);
        var candidates=recommendations.view(parent,owner.getLoginId(),null).candidates();
        jobs.findById(parent).orElseThrow().completeRecommendation(
            mapper.writeValueAsString(List.of(new RankedDestination(1,candidates.getFirst(),"추천"))));
        em.flush();
        var id=schedules.create(parent,candidates.getFirst().placeId(),owner.getLoginId(),null,
            LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var work=schedules.claim(id);
        schedules.complete(work,schedules.generate(work));
        em.flush();
        assertThatThrownBy(()->saved.save(id,owner.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
            .hasMessageContaining("출발일");
    }
}
