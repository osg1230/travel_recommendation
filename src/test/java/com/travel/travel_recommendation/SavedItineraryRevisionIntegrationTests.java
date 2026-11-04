package com.travel.travel_recommendation;

import com.travel.travel_recommendation.service.KoreaTime;
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
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 저장한 일정에 수정안을 적용하는 흐름(SFR-08-04)과 오래된 결과의 덮어쓰기 방지를 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class SavedItineraryRevisionIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired SavedItineraryService saved;
    @Autowired SavedItineraryEditService edits;
    @Autowired PlaceCandidateService placeCandidates;
    @Autowired RecommendationHistoryService histories;
    @Autowired ItineraryRepository itineraries;
    @Autowired AiJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    // 장소 수집(refresh)이 실제 관광공사·GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 빈 목록을 돌려준다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private User owner;
    private UUID original;
    private Long itineraryId;

    @BeforeEach void setup() {
        // 개발 DB에 최근 확인한 카카오 장소가 있으면 후보가 PC마다 달라지므로 쓰지 않게 하고, 강릉 기본 관광지 2곳에 테스트 관광지 3곳을 더한다(롤백됨).
        ExternalPlaceCache.expireExisting(jdbc);
        for(int i=1;i<=3;i++) insertAttraction("TEST_SAVED_"+i,300+i,"하슬라 테스트 관광지"+i,"테스트");
        owner=member();
        when(maps.locate(anyString())).thenAnswer(call->new KakaoMapClient.Location(call.getArgument(0),128.9+(Math.abs(call.getArgument(0).hashCode()%1000))/100000.0,37.7,Instant.now()));
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
        original=completedDraft(owner);
        itineraryId=saved.save(original,owner.getLoginId()).id();
        em.flush(); em.clear();
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
        jobs.findById(parent).orElseThrow().completeRecommendation(
            mapper.writeValueAsString(List.of(new RankedDestination(1,candidates.getFirst(),"추천"))));
        em.flush();
        var id=schedules.create(parent,candidates.getFirst().placeId(),user.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),
            KakaoMapClient.Transport.CAR,90);
        finish(id); return id;
    }
    private void finish(UUID id) {
        var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush();
    }
    private UUID revision(UUID source,LocalTime start) {
        var id=schedules.revise(source,owner.getLoginId(),null,List.of(),null,start,LocalTime.of(19,0),90);
        finish(id); return id;
    }
    private LocalTime firstStart() {
        em.flush(); em.clear();
        return saved.detail(itineraryId,owner.getLoginId()).days().getFirst().items().getFirst().start();
    }

    @Test void appliesRevisionAndNextEditStartsFromLatestApplied() {
        var detail=saved.detail(itineraryId,owner.getLoginId());
        assertThat(detail.editableJobId()).isEqualTo(original);
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));

        var revised=revision(original,LocalTime.of(10,0));
        var targets=saved.targets(revised,owner.getLoginId());
        assertThat(targets).extracting(SavedItinerary.Target::id).containsExactly(itineraryId);
        // 적용 전에는 저장한 일정이 그대로다.
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));

        saved.apply(itineraryId,revised,targets.getFirst().version(),owner.getLoginId());
        assertThat(firstStart()).isEqualTo(LocalTime.of(10,0));
        var after=saved.detail(itineraryId,owner.getLoginId());
        assertThat(after.editableJobId()).isEqualTo(revised);
        assertThat(after.summary().title()).isEqualTo(detail.summary().title());

        // 적용한 수정안에서 다시 만든 수정안도 이어서 적용할 수 있다.
        var second=revision(revised,LocalTime.of(11,0));
        saved.apply(itineraryId,second,saved.targets(second,owner.getLoginId()).getFirst().version(),owner.getLoginId());
        assertThat(firstStart()).isEqualTo(LocalTime.of(11,0));
    }

    // 적용하면 바뀐 부분을 보여 주고, 직전 일정으로 되돌렸다가 한 번 더 누르면 되돌리기를 취소한다(직전 한 단계만).
    @Test void undoRestoresPreviousVersionAndUndoAgainRedoes() {
        var before=saved.detail(itineraryId,owner.getLoginId());
        assertThat(before.lastChange()).isNull();
        assertThatThrownBy(()->saved.undo(itineraryId,before.version(),owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("되돌릴 수정 내역이 없어요");

        var revised=revision(original,LocalTime.of(10,0));
        saved.apply(itineraryId,revised,saved.targets(revised,owner.getLoginId()).getFirst().version(),owner.getLoginId());
        em.flush(); em.clear();
        var applied=saved.detail(itineraryId,owner.getLoginId());
        assertThat(applied.lastChange().undone()).isFalse();
        assertThat(applied.lastChange().changedAt()).isNotNull();
        assertThat(applied.lastChange().comparison().settings()).contains("하루 일정 시간: 09:00~18:00 → 10:00~19:00");
        assertThat(applied.lastChange().comparison().changes()).isNotEmpty();

        assertThat(saved.undo(itineraryId,applied.version(),owner.getLoginId())).isTrue();
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));
        var undone=saved.detail(itineraryId,owner.getLoginId());
        assertThat(undone.days()).isEqualTo(before.days());
        assertThat(undone.editableJobId()).isEqualTo(original);
        assertThat(undone.summary().title()).isEqualTo(before.summary().title());
        assertThat(undone.lastChange().undone()).isTrue();
        assertThat(undone.lastChange().comparison().settings()).contains("하루 일정 시간: 10:00~19:00 → 09:00~18:00");
        // 되돌린 일정에는 수정 전 조건 결과가 다시 남는다.
        assertThat(undone.checks()).isEqualTo(before.checks());

        assertThat(saved.undo(itineraryId,undone.version(),owner.getLoginId())).isFalse();
        assertThat(firstStart()).isEqualTo(LocalTime.of(10,0));
        var redone=saved.detail(itineraryId,owner.getLoginId());
        assertThat(redone.days()).isEqualTo(applied.days());
        assertThat(redone.editableJobId()).isEqualTo(revised);
        assertThat(redone.lastChange().undone()).isFalse();
        // 오래된 화면 버전으로는 되돌리지 않는다.
        assertThatThrownBy(()->saved.undo(itineraryId,undone.version(),owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("먼저 바꿨어요");
        // 되돌리기 취소 뒤에도 그 일정에서 만든 수정안을 이어서 적용할 수 있다.
        var next=revision(revised,LocalTime.of(11,0));
        saved.apply(itineraryId,next,saved.targets(next,owner.getLoginId()).getFirst().version(),owner.getLoginId());
        assertThat(firstStart()).isEqualTo(LocalTime.of(11,0));
    }

    @Test void screensShowChangesAndUndo() throws Exception {
        var revised=revision(original,LocalTime.of(10,0));
        // 수정안 화면은 원래 초안과 비교해 달라진 부분을 보여 준다.
        mvc.perform(get("/travel/recommendations/itineraries/"+revised).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("원래 초안과 달라진 부분")))
            .andExpect(content().string(containsString("하루 일정 시간: 09:00~18:00 → 10:00~19:00")));
        String version=saved.detail(itineraryId,owner.getLoginId()).version();
        mvc.perform(get("/my/itineraries/"+itineraryId).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(org.hamcrest.Matchers.not(containsString("수정 전으로 되돌리기"))));
        mvc.perform(post("/my/itineraries/"+itineraryId+"/apply").with(user(owner.getLoginId())).with(csrf())
                .param("jobId",revised.toString()).param("version",version))
            .andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        mvc.perform(get("/my/itineraries/"+itineraryId).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("최근 수정에서 바뀐 부분")))
            .andExpect(content().string(containsString("수정 전으로 되돌리기")))
            .andExpect(content().string(containsString("change-badge")));
        String applied=saved.detail(itineraryId,owner.getLoginId()).version();
        var other=member();
        mvc.perform(post("/my/itineraries/"+itineraryId+"/undo").with(user(other.getLoginId())).with(csrf()).param("version",applied))
            .andExpect(status().isNotFound());
        mvc.perform(post("/my/itineraries/"+itineraryId+"/undo").with(user(owner.getLoginId())).with(csrf()).param("version",applied))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/my/itineraries/"+itineraryId))
            .andExpect(flash().attribute("savedMessage","수정 전 일정으로 되돌렸어요."));
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));
        mvc.perform(get("/my/itineraries/"+itineraryId).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("되돌리기로 바뀐 부분")))
            .andExpect(content().string(containsString("되돌리기 취소하기")));
    }

    @Test void staleRevisionOrVersionCannotOverwriteLatest() {
        var first=revision(original,LocalTime.of(10,0));
        var sibling=revision(original,LocalTime.of(8,0));
        String version=saved.targets(first,owner.getLoginId()).getFirst().version();
        saved.apply(itineraryId,first,version,owner.getLoginId());
        em.flush(); em.clear();
        String latest=saved.detail(itineraryId,owner.getLoginId()).version();

        // 원본에서 만든 다른 수정안은 최근 적용 일정에서 이어지지 않아 거부한다.
        assertThatThrownBy(()->saved.apply(itineraryId,sibling,latest,owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("가장 최근에 적용한");
        assertThat(saved.targets(sibling,owner.getLoginId())).isEmpty();
        // 이미 적용한 수정안을 다시 적용하지 않는다.
        assertThatThrownBy(()->saved.apply(itineraryId,first,latest,owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("가장 최근에 적용한");
        // 적용 전의 화면 버전으로는 덮어쓰지 않는다.
        var next=revision(first,LocalTime.of(11,0));
        assertThatThrownBy(()->saved.apply(itineraryId,next,version,owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("먼저 바꿨어요");
        assertThat(firstStart()).isEqualTo(LocalTime.of(10,0));
    }

    @Test void otherMemberAndUnfinishedRevisionAreRefused() {
        var pending=schedules.revise(original,owner.getLoginId(),null,List.of(),null,LocalTime.of(10,0),LocalTime.of(19,0),90);
        String version=saved.detail(itineraryId,owner.getLoginId()).version();
        assertThatThrownBy(()->saved.apply(itineraryId,pending,version,owner.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("완성되지 않은");
        finish(pending);
        var other=member();
        assertThatThrownBy(()->saved.apply(itineraryId,pending,version,other.getLoginId()))
            .isInstanceOf(ResponseStatusException.class);
        assertThat(saved.targets(pending,other.getLoginId())).isEmpty();
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));
    }

    @Test void screensOfferEditAndApply() throws Exception {
        mvc.perform(get("/my/itineraries/"+itineraryId).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("수정안 만들러 가기")))
            .andExpect(content().string(containsString("바꿀 수 없는 것")))
            .andExpect(content().string(containsString("이 장소 평가하기")));
        var revised=revision(original,LocalTime.of(10,0));
        mvc.perform(get("/travel/recommendations/itineraries/"+revised).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("저장한 일정에 적용")))
            .andExpect(content().string(containsString("수정안으로 바꿀 수 있는 것")));
        String version=saved.detail(itineraryId,owner.getLoginId()).version();
        mvc.perform(post("/my/itineraries/"+itineraryId+"/apply").with(user(owner.getLoginId())).with(csrf())
                .param("jobId",revised.toString()).param("version",version))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/my/itineraries/"+itineraryId));
        mvc.perform(post("/my/itineraries/"+itineraryId+"/apply").with(user(owner.getLoginId())).with(csrf())
                .param("jobId",revised.toString()).param("version",version))
            .andExpect(status().isConflict());
        assertThat(firstStart()).isEqualTo(LocalTime.of(10,0));
    }

    // 이 회원의 모든 작업을 보관 기간이 지난 상태로 만든다.
    private void expireJobs() {
        em.flush();
        em.createQuery("update AiJob j set j.expiresAt=:past where j.userId=:user")
            .setParameter("past",KoreaTime.now().minusMinutes(1)).setParameter("user",owner.getId()).executeUpdate();
        em.clear();
    }
    private void editRestoredDraftAndApply(UUID draft) {
        var detail=saved.detail(itineraryId,owner.getLoginId());
        assertThat(detail.editableJobId()).isEqualTo(draft);
        // 다시 만든 초안은 저장한 시각을 그대로 가진다.
        var view=schedules.view(draft,owner.getLoginId(),null);
        assertThat(view.result().days().getFirst().items().getFirst().start()).isEqualTo(LocalTime.of(9,0));
        var revised=revision(draft,LocalTime.of(10,0));
        var targets=saved.targets(revised,owner.getLoginId());
        assertThat(targets).extracting(SavedItinerary.Target::id).containsExactly(itineraryId);
        saved.apply(itineraryId,revised,targets.getFirst().version(),owner.getLoginId());
        assertThat(firstStart()).isEqualTo(LocalTime.of(10,0));
    }

    @Test void expiredDraftIsRestoredFromSavedItinerary() {
        Long requestId=itineraries.findById(itineraryId).orElseThrow().getRequestId();
        String version=saved.detail(itineraryId,owner.getLoginId()).version();
        expireJobs();
        assertThat(saved.detail(itineraryId,owner.getLoginId()).editableJobId()).isNull();

        var draft=edits.prepare(itineraryId,owner.getLoginId());
        assertThat(draft.jobId()).isNotEqualTo(original);
        assertThat(draft.missingPlaces()).isEmpty();
        em.flush(); em.clear();
        // 초안만 다시 연결했으므로 저장 일정 버전과 내용은 그대로다.
        assertThat(saved.detail(itineraryId,owner.getLoginId()).version()).isEqualTo(version);
        assertThat(firstStart()).isEqualTo(LocalTime.of(9,0));
        // 다시 눌러도 같은 초안을 쓴다.
        assertThat(edits.prepare(itineraryId,owner.getLoginId()).jobId()).isEqualTo(draft.jobId());

        editRestoredDraftAndApply(draft.jobId());
        // 다시 만든 초안은 추천 이력에 연결하지 않지만 저장 일정의 기존 이력 연결은 유지한다.
        assertThat(itineraries.findById(itineraryId).orElseThrow().getRequestId()).isEqualTo(requestId);
    }

    // 동선 주변에서 따로 찾은 음식점처럼 지금 후보에 없는 식당도 다시 만든 초안의 후보에 넣어 미충족으로 보지 않는다.
    @Test void restoredDraftKeepsSavedRestaurantMissingFromCurrentCandidates() {
        em.createNativeQuery("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('RESTAURANT','SYSTEM','TEST_RESTORE_MEAL',N'테스트 다른 지역 식당',N'강원특별자치도 속초시',N'강원특별자치도 속초시 테스트로 1',
                   N'한식',N'테스트 식당',N'https://www.sokcho.go.kr/',DATEADD(day,-1,SYSDATETIME()))""").executeUpdate();
        Long restaurant=((Number)em.createNativeQuery("SELECT place_id FROM dbo.places WHERE source_place_id='TEST_RESTORE_MEAL'").getSingleResult()).longValue();
        int meals=em.createNativeQuery("UPDATE dbo.itinerary_items SET place_id=?1 WHERE itinerary_id=?2 AND item_type='MEAL'")
            .setParameter(1,restaurant).setParameter(2,itineraryId).executeUpdate();
        assertThat(meals).isPositive();
        expireJobs();

        var draft=edits.prepare(itineraryId,owner.getLoginId());
        em.flush(); em.clear();
        var view=schedules.view(draft.jobId(),owner.getLoginId(),null);
        assertThat(view.input().places().restaurants()).extracting(PlaceCandidate::placeId).contains(restaurant);
        assertThat(ItineraryConditionReport.evaluate(view.input(),view.result()).checks())
            .extracting(ItineraryConditionReport.Check::status).doesNotContain(ItineraryConditionReport.Status.UNMET);
    }

    /**
     * 저장한 관광지가 후보 순서가 바뀌어 지금 후보 상위 N곳에서 밀려났어도, 지금 확인된 장소면 시간만 바꾼 수정안을 만들 수 있다.
     * 저장한 뒤에 늘어난 조건 항목(purposeSuggested)이 없는 예전 저장 기록도 읽고, 저장한 숙소는 수정안에서 그대로 쓴다.
     * 2026-10-31 실제 앱에서 "유지한 관광지의 정보가 바뀌었어요"(밀려난 관광지)와 500 오류(예전 저장 기록)로 막혔던 경우다.
     */
    @Test void restoredDraftKeepsPlacesOutsideCurrentCandidatesAndSavedStay() {
        // 이름순으로 앞서는 확인된 관광지 16곳(후보 수 상한)을 넣어, 저장한 관광지를 후보 목록 밖으로 밀어낸다.
        // 종류(subtype)를 모두 달리해 같은 종류를 뒤로 보내는 규칙에 걸리지 않게 한다.
        for(int i=1;i<=TripConstraints.candidateLimit(2);i++) insertAttraction("TEST_PUSH_"+i,100+i,"가테스트관광지"+String.format("%02d",i),"테스트분류"+i);
        Long pushed=insertAttraction("TEST_PUSHED",200,"힣테스트관광지","테스트밀린분류");
        Long visit=((Number)em.createNativeQuery("""
            SELECT TOP 1 item_id FROM dbo.itinerary_items WHERE itinerary_id=?1 AND item_type='VISIT' ORDER BY day_no,sequence_no""")
            .setParameter(1,itineraryId).getSingleResult()).longValue();
        em.createNativeQuery("UPDATE dbo.itinerary_items SET place_id=?1 WHERE item_id=?2").setParameter(1,pushed).setParameter(2,visit).executeUpdate();
        em.createNativeQuery("UPDATE dbo.itineraries SET conditions_snapshot_json=JSON_MODIFY(conditions_snapshot_json,'$.conditions.purposeSuggested',NULL) WHERE itinerary_id=?1")
            .setParameter(1,itineraryId).executeUpdate();
        Long stay=((Number)em.createNativeQuery("SELECT TOP 1 place_id FROM dbo.itinerary_items WHERE itinerary_id=?1 AND item_type='STAY'")
            .setParameter(1,itineraryId).getSingleResult()).longValue();
        expireJobs();

        var draft=edits.prepare(itineraryId,owner.getLoginId());
        em.flush(); em.clear();
        assertThat(draft.missingPlaces()).isEmpty();
        var view=schedules.view(draft.jobId(),owner.getLoginId(),null);
        // 저장한 관광지가 정말 지금 후보 목록 밖인지 확인한다(안에 있으면 이 테스트가 보려는 상황이 아니다).
        assertThat(placeCandidates.findForSchedule(view.input().recommendationId(),view.input().places().destination().placeId(),
                owner.getLoginId(),null,view.input().conditions()).attractions()).extracting(PlaceCandidate::placeId).doesNotContain(pushed);
        assertThat(view.input().choices().stayPlaceId()).isEqualTo(stay);
        assertThat(ItineraryConditionReport.evaluate(view.input(),view.result()).checks())
            .extracting(ItineraryConditionReport.Check::status).doesNotContain(ItineraryConditionReport.Status.UNMET);

        var revised=revision(draft.jobId(),LocalTime.of(10,0));
        var result=schedules.view(revised,owner.getLoginId(),null);
        assertThat(result.input().places().attractions()).extracting(PlaceCandidate::placeId).contains(pushed);
        assertThat(result.result().days().getFirst().items()).filteredOn(item->"STAY".equals(item.type()))
            .extracting(ItineraryPlan.Item::placeId).containsOnly(stay);
    }
    private Long insertAttraction(String sourceId,int number,String name,String subtype) {
        em.createNativeQuery("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('ATTRACTION','SYSTEM',?1,?2,N'강원특별자치도 강릉시',?3,?4,N'테스트 관광지',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""")
            .setParameter(1,sourceId).setParameter(2,name).setParameter(3,"강원특별자치도 강릉시 테스트로 "+number).setParameter(4,subtype).executeUpdate();
        return ((Number)em.createNativeQuery("SELECT place_id FROM dbo.places WHERE source_place_id=?1").setParameter(1,sourceId).getSingleResult()).longValue();
    }

    // 하루 방문 수 같은 사용자 조건은 저장 기록에 남아, 원래 초안이 만료된 뒤 다시 만든 초안에도 이어진다.
    @Test void restoredDraftKeepsUserConstraints() {
        var start=LocalDate.now().plusDays(7);
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(),"");
        var parent=recommendations.create(conditions,owner.getLoginId(),null);
        recommendations.prepare(parent);
        var candidates=recommendations.view(parent,owner.getLoginId(),null).candidates();
        jobs.findById(parent).orElseThrow().completeRecommendation(
            mapper.writeValueAsString(List.of(new RankedDestination(1,candidates.getFirst(),"추천"))));
        em.flush();
        var draft=schedules.create(parent,candidates.getFirst().placeId(),owner.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),
            KakaoMapClient.Transport.CAR,90,new ItineraryPlan.Constraints(List.of(),2,false,null,List.of(),"BUDGET"));
        finish(draft);
        Long id=saved.save(draft,owner.getLoginId()).id();
        expireJobs();

        var restored=edits.prepare(id,owner.getLoginId());
        em.flush(); em.clear();
        var constraints=schedules.view(restored.jobId(),owner.getLoginId(),null).input().constraints();
        assertThat(constraints).isNotNull();
        assertThat(constraints.dailyVisits()).isEqualTo(2);
        assertThat(constraints.stayTier()).isEqualTo("BUDGET");
    }

    @Test void deletedHistoryDraftIsRestoredFromSavedItinerary() {
        Long requestId=itineraries.findById(itineraryId).orElseThrow().getRequestId();
        histories.delete(requestId,owner.getLoginId());
        em.flush(); em.clear();
        assertThat(jobs.findById(original)).isEmpty();
        assertThat(saved.detail(itineraryId,owner.getLoginId()).editableJobId()).isNull();

        var draft=edits.prepare(itineraryId,owner.getLoginId());
        em.flush(); em.clear();
        editRestoredDraftAndApply(draft.jobId());
    }

    @Test void restoreScreenAndOtherMemberRefused() throws Exception {
        expireJobs();
        mvc.perform(get("/my/itineraries/"+itineraryId).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("수정 준비하기")));
        var other=member();
        mvc.perform(post("/my/itineraries/"+itineraryId+"/edit").with(user(other.getLoginId())).with(csrf()))
            .andExpect(status().isNotFound());
        mvc.perform(post("/my/itineraries/"+itineraryId+"/edit").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(status().is3xxRedirection())
            .andExpect(redirectedUrlPattern("/travel/recommendations/itineraries/*#revise-title"));
        em.flush(); em.clear();
        var draft=saved.detail(itineraryId,owner.getLoginId()).editableJobId();
        assertThat(draft).isNotNull().isNotEqualTo(original);
        mvc.perform(get("/travel/recommendations/itineraries/"+draft).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("일정 수정하기")));
    }

    @Test void savedDetailKeepsChecksAndPricesUntilRevisionReplacesThem() throws Exception {
        // 참고 가격을 찾은 초안을 저장하면 조건 결과와 가격이 저장 상세로 이어진다.
        var draft=completedDraft(owner);
        var job=jobs.findById(draft).orElseThrow();
        var result=mapper.readValue(job.getResultJson(),ItineraryPlan.Result.class);
        var meal=result.days().stream().flatMap(d->d.items().stream()).filter(i->"MEAL".equals(i.type())).findFirst().orElseThrow();
        var checkedAt=Instant.parse("2026-09-23T03:00:00Z");
        var quote=new ItineraryPlan.PriceQuote(meal.placeId(),meal.name(),"RESTAURANT","VERIFIED","1인 12,000원",12000,12000,
            "대표 메뉴 기준","https://example.com/menu",checkedAt);
        schedules.savePrices(draft,owner.getLoginId(),null,job.getResultJson(),new ItineraryPlan.Prices(List.of(quote),checkedAt));
        em.flush(); em.clear();
        Long id=saved.save(draft,owner.getLoginId()).id();
        em.flush(); em.clear();

        var detail=saved.detail(id,owner.getLoginId());
        assertThat(detail.checks()).isNotEmpty().extracting(SavedItinerary.Check::status).doesNotContain("UNMET");
        assertThat(detail.prices()).extracting(ItineraryPlan.PriceQuote::priceText).containsExactly("1인 12,000원");
        assertThat(detail.pricesCheckedAt()).isEqualTo("2026-09-23 12:00");
        assertThat(detail.price(meal.placeId())).isNotNull();
        mvc.perform(get("/my/itineraries/"+id).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("저장 당시 확인한 정보")))
            .andExpect(content().string(containsString("저장 당시 참고 가격: 1인 12,000원")));

        // 가격을 찾지 않은 수정안을 적용하면 이전 초안의 가격은 남기지 않는다.
        var revised=revision(draft,LocalTime.of(10,0));
        saved.apply(id,revised,saved.targets(revised,owner.getLoginId()).getFirst().version(),owner.getLoginId());
        em.flush(); em.clear();
        var after=saved.detail(id,owner.getLoginId());
        assertThat(after.prices()).isEmpty();
        assertThat(after.pricesCheckedAt()).isNull();
        assertThat(after.checks()).isNotEmpty();
    }
}
