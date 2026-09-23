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
    @Autowired RecommendationHistoryService histories;
    @Autowired ItineraryRepository itineraries;
    @Autowired AiJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    @MockitoBean TourApiClient tour;
    private User owner;
    private UUID original;
    private Long itineraryId;

    @BeforeEach void setup() {
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
            .andExpect(content().string(containsString("이 장소 평가하기")));
        var revised=revision(original,LocalTime.of(10,0));
        mvc.perform(get("/travel/recommendations/itineraries/"+revised).with(user(owner.getLoginId())))
            .andExpect(status().isOk()).andExpect(content().string(containsString("저장한 일정에 적용")));
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
            .setParameter("past",LocalDateTime.now(ZoneOffset.UTC).minusMinutes(1)).setParameter("user",owner.getId()).executeUpdate();
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
            .andExpect(status().isOk()).andExpect(content().string(containsString("장소 바꿔서 수정안 만들기")));
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
