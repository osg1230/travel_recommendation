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
    // 장소 수집(refresh)이 실제 관광공사·GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 빈 목록을 돌려준다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    @MockitoBean OpenAiRevisionClient ai;
    @MockitoBean OpenAiVerificationClient verifier;
    private MockHttpSession session;
    private byte[] hash;
    private UUID parent;
    private long destination;

    /** 이 테스트의 비회원이 만든 작업 수. 웹 서버를 켜 둔 채 돌려도 다른 사람의 작업 때문에 개수가 어긋나지 않게 전체 수를 세지 않는다. */
    private long ownJobs() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM dbo.ai_jobs WHERE guest_token_hash=?",Long.class,hash);
    }

    @BeforeEach void setup() throws Exception {
        ExternalPlaceCache.expireExisting(jdbc);
        // 강릉 기본 관광지는 2곳뿐이라 교체 후보를 확인할 수 있게 테스트 관광지를 추가한다(롤백됨).
        // 이름순 정렬에서 뒤에 오도록 이름을 정해, 2일 일정에는 앞의 4곳이 쓰이고 마지막 1곳이 교체 후보로 남는다.
        String[][] extra={{"하슬라 테스트 정원","1001"},{"해변 테스트 공원","1002"},{"허브 테스트 실내 체험관","1003"}};
        for(var place:extra) jdbc.update("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('ATTRACTION','SYSTEM',?,?,N'강원특별자치도 강릉시',?,N'테스트',N'테스트 관광지',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""",
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
    // 09:00~15:00이면 하루 약 2곳이라 2일 초안에 관광지 4곳을 쓰고, 나머지 1곳은 교체 후보로 남는다.
    // 하루 개수는 종류별 체류시간 평균으로 정한다(2026-11-16). 경포대(누각 40분) 때문에 평균이 80분이라, 16:00까지면 하루 3곳이 되어 교체 후보가 없어진다.
    private UUID completedDraft() {
        var id=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(15,0),KakaoMapClient.Transport.CAR,90);
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

    // 원본 초안이 시간·거리 때문에 뺀 관광지(skipped)는 교체 후보로 다시 쓰지 않는다(섬처럼 갈 수 없는 곳이 돌아오지 않게).
    @Test void placesLeftOutOfTheDraftAreNotUsedAsReplacements() {
        var original=completedDraft();
        var ids=attractionIds(original);
        var job=jobs.findById(original).orElseThrow();
        var result=mapper.readValue(job.getResultJson(),ItineraryPlan.Result.class);
        var left=schedules.view(original,null,hash).input().places().attractions().getLast();
        // 초안에서 그 관광지를 뺀 것처럼 방문 항목을 지우고 skipped에 적는다.
        var days=result.days().stream().map(d->new ItineraryPlan.Day(d.number(),d.date(),
            d.items().stream().filter(i->!("VISIT".equals(i.type()) && i.placeId().equals(left.placeId()))).toList())).toList();
        var leftOut=new ItineraryPlan.Result(result.destination(),days,result.transport(),result.createdAt())
            .withSkipped(List.of(new PlaceRef(left.placeId(),left.name())));
        job.attachToResult(mapper.writeValueAsString(leftOut));
        em.flush();
        var revised=schedules.revise(original,null,hash,List.of(ids.getFirst()),ReplaceReason.TASTE,LocalTime.of(9,0),LocalTime.of(18,0),90);
        var revision=schedules.view(revised,null,hash).input().revision();
        assertThat(revision.added()).extracting(PlaceRef::placeId).doesNotContain(left.placeId());
        assertThat(revision.kept()).extracting(PlaceRef::placeId).doesNotContain(left.placeId());
    }

    private OpenAiRevisionClient.Proposal revise(List<Long> replace,List<Long> add,LocalTime start) {
        return new OpenAiRevisionClient.Proposal(false,null,replace,add,start,null,null,"박물관 대신 바다 쪽 장소로 바꿨어요.");
    }
    private long spareId(String name) {
        return schedules.attractionPool(parent,destination,null,hash).stream().filter(p->p.name().equals(name)).findFirst().orElseThrow().placeId();
    }

    @Test void naturalLanguageRequestCreatesRevisionFromChosenCandidates() {
        var original=completedDraft();
        var target=attractionIds(original).getFirst();
        long herb=spareId("허브 테스트 실내 체험관");
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("첫 번째 곳 빼고 실내로, 10시 시작"),any()))
            .thenReturn(revise(List.of(target),List.of(herb),LocalTime.of(10,0)));
        var result=schedules.aiRevise(original,null,hash,"첫 번째 곳 빼고 실내로, 10시 시작");
        assertThat(result.question()).isNull();
        var input=schedules.view(result.job(),null,hash).input();
        assertThat(input.start()).isEqualTo(LocalTime.of(10,0));
        assertThat(input.revision().reason()).isEqualTo("AI_REQUEST");
        assertThat(input.revision().request()).isEqualTo("첫 번째 곳 빼고 실내로, 10시 시작");
        assertThat(input.revision().explanation()).contains("바다");
        assertThat(input.revision().added()).extracting(PlaceRef::placeId).containsExactly(herb);
        assertThat(attractionIds(result.job())).doesNotContain(target).contains(herb);
        finish(result.job());
    }

    // 문장 요청 하나로 관광지 더하기(빼지 않고)·이동수단·식당 빼기·숙소 고르기를 함께 반영한 수정안을 만든다.
    @Test void naturalLanguageRequestCanAddPlaceChangeTransportRestaurantAndStay() {
        // 강릉 기본 숙소는 한 곳뿐이라 바꿔 고를 숙소를 하나 더 넣는다(롤백됨).
        jdbc.update("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('ACCOMMODATION','SYSTEM','TEST_REVISION_STAY',N'테스트 바다 호텔',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 경포로 1100',
                   N'호텔',N'테스트 숙소',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""");
        // 식당도 한 곳뿐이면 빼 달라고 해도 다른 곳이 없어 그대로 쓰므로 식당을 하나 더 넣는다.
        jdbc.update("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            VALUES('RESTAURANT','SYSTEM','TEST_REVISION_MEAL',N'테스트 순두부집',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 경포로 1200',
                   N'한식',N'테스트 식당',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""");
        var original=completedDraft();
        var view=schedules.view(original,null,hash);
        long herb=spareId("허브 테스트 실내 체험관");
        long lunch=view.result().days().getFirst().items().stream().filter(i->i.type().equals("MEAL")).findFirst().orElseThrow().placeId();
        long otherStay=view.input().places().accommodations().stream().map(PlaceCandidate::placeId)
            .filter(id->!id.equals(view.result().lodging().chosenPlaceId())).findFirst().orElseThrow();
        String text="체험관도 넣고 대중교통으로 갈게요. 그 식당은 빼고 숙소는 다른 곳으로";
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq(text),any())).thenReturn(new OpenAiRevisionClient.Proposal(false,null,
            List.of(),List.of(herb),null,null,null,"체험관을 더하고 대중교통으로 바꿨어요.",false,List.of(),false,null,null,null,
            KakaoMapClient.Transport.PUBLIC_TRANSIT,List.of(lunch),List.of(),otherStay));
        var result=schedules.aiRevise(original,null,hash,text);
        assertThat(result.question()).isNull();
        var input=schedules.view(result.job(),null,hash).input();
        assertThat(input.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(input.choices().avoidRestaurants()).containsExactly(lunch);
        assertThat(input.choices().stayPlaceId()).isEqualTo(otherStay);
        assertThat(input.revision().replaced()).isEmpty();
        assertThat(input.revision().added()).extracting(PlaceRef::placeId).containsExactly(herb);
        finish(result.job());
        var done=schedules.view(result.job(),null,hash).result();
        assertThat(done.lodging().chosenPlaceId()).isEqualTo(otherStay);
        assertThat(done.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("MEAL")).map(ItineraryPlan.Item::placeId)).doesNotContain(lunch);
        assertThat(attractionIds(result.job())).contains(herb).containsAll(attractionIds(original));
    }

    // 콕 집은 장소가 후보에 없으면 처음에는 되묻고, 같은 장소를 다시 요청하면 비슷한 곳으로 바꾼 수정안에 참고 설명을 남긴다.
    // 비슷한 곳을 원한 요청은 되묻지 않고 바로 바꾼다.
    @Test void unavailableExactPlaceIsAskedFirstThenRevisedWithNote() throws Exception {
        var original=completedDraft();
        var target=attractionIds(original).getFirst();
        long herb=spareId("허브 테스트 실내 체험관");
        long before=ownJobs();
        when(ai.configured()).thenReturn(true);
        var exact=new OpenAiRevisionClient.Proposal(false,null,List.of(target),List.of(herb),null,null,null,"비슷한 실내 체험관으로 바꿨어요.",false,
            List.of("전동성당"),true);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("첫 번째 곳을 전동성당으로 바꿔 줘"),any())).thenReturn(exact);
        var asked=schedules.aiRevise(original,null,hash,"첫 번째 곳을 전동성당으로 바꿔 줘",Set.of());
        assertThat(asked.job()).isNull();
        assertThat(asked.question()).contains("'전동성당'은(는) 이 지역에서 확인된 관광지로 찾지 못했어요").contains("한 번 더 보내면");
        assertThat(asked.askedPlaces()).containsExactly("전동성당");
        assertThat(ownJobs()).isEqualTo(before);

        var again=schedules.aiRevise(original,null,hash,"첫 번째 곳을 전동성당으로 바꿔 줘",Set.of("전동성당"));
        var revision=schedules.view(again.job(),null,hash).input().revision();
        assertThat(revision.missingPlaces()).containsExactly("전동성당");
        assertThat(revision.added()).extracting(PlaceRef::placeId).containsExactly(herb);
        finish(again.job());
        mvc.perform(get("/travel/recommendations/itineraries/"+again.job()).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("참고: 요청한 &#39;전동성당&#39;은(는) 확인된 교체 후보에 없어서 비슷한 곳으로 바꿨어요.")));

        var similar=new OpenAiRevisionClient.Proposal(false,null,List.of(target),List.of(herb),null,null,null,"비슷한 곳으로 바꿨어요.",false,
            List.of("전동성당"),false);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("첫 번째 곳을 전동성당 같은 곳으로"),any())).thenReturn(similar);
        var direct=schedules.aiRevise(original,null,hash,"첫 번째 곳을 전동성당 같은 곳으로",Set.of());
        assertThat(direct.job()).isNotNull();
        assertThat(schedules.view(direct.job(),null,hash).input().revision().missingPlaces()).containsExactly("전동성당");
    }

    @Test void ambiguousRequestReturnsQuestionAndFormatFailuresAreRetriedAtMostTwice() {
        var original=completedDraft();
        long before=ownJobs();
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("좀 바꿔 줘"),any()))
            .thenReturn(new OpenAiRevisionClient.Proposal(true,"어느 관광지를 바꿀까요?",List.of(),List.of(),null,null,null,null));
        var clarify=schedules.aiRevise(original,null,hash,"좀 바꿔 줘");
        assertThat(clarify.job()).isNull();
        assertThat(clarify.question()).isEqualTo("어느 관광지를 바꿀까요?");
        assertThat(ownJobs()).isEqualTo(before);

        // 형식 오류는 사유를 붙여 다시 요청하고, 세 번째도 실패하면 그대로 알린다.
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("형식 오류"),any()))
            .thenThrow(new RecommendationException("AI_INVALID_RESPONSE","replace와 add의 개수가 같아야 해요."));
        assertThatThrownBy(()->schedules.aiRevise(original,null,hash,"형식 오류")).isInstanceOf(RecommendationException.class);
        verify(ai,times(3)).propose(any(),any(),any(),any(),any(),any(),eq("형식 오류"),any());
        verify(ai,times(2)).propose(any(),any(),any(),any(),any(),any(),eq("형식 오류"),eq("replace와 add의 개수가 같아야 해요."));
        // 인증 실패처럼 형식 문제가 아니면 다시 요청하지 않는다.
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("인증"),any())).thenThrow(new RecommendationException("AI_AUTH","설정 확인"));
        assertThatThrownBy(()->schedules.aiRevise(original,null,hash,"인증")).isInstanceOf(RecommendationException.class);
        verify(ai,times(1)).propose(any(),any(),any(),any(),any(),any(),eq("인증"),any());
        // 바꿀 내용이 없는 수정안은 새 작업을 만들지 않는다.
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("그대로"),any())).thenReturn(revise(List.of(),List.of(),null));
        assertThat(schedules.aiRevise(original,null,hash,"그대로").question()).contains("바꿀 내용을 찾지 못했어요");
        assertThat(ownJobs()).isEqualTo(before);
        // 경계값: 수정 요청은 1~300자라 301자는 AI를 부르기 전에 거절하고, 300자는 AI에 넘긴다(점검 보고 29번).
        String tooLong="가".repeat(301), longest="가".repeat(300);
        assertThatThrownBy(()->schedules.aiRevise(original,null,hash,tooLong))
            .isInstanceOfSatisfying(org.springframework.web.server.ResponseStatusException.class,
                e->assertThat(e.getStatusCode().value()).isEqualTo(400));
        verify(ai,never()).propose(any(),any(),any(),any(),any(),any(),eq(tooLong),any());
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq(longest),any())).thenReturn(revise(List.of(),List.of(),null));
        assertThat(schedules.aiRevise(original,null,hash,longest).question()).contains("바꿀 내용을 찾지 못했어요");
        assertThatThrownBy(()->schedules.aiRevise(original,null,hash," ")).isInstanceOf(ResponseStatusException.class);
    }

    @Test void aiRequestScreenShowsQuestionOrUnavailableNotice() throws Exception {
        var original=completedDraft();
        var url="/travel/recommendations/itineraries/"+original+"/ai-revisions";
        when(ai.configured()).thenReturn(false);
        mvc.perform(post(url).session(session).with(csrf()).param("request","바다로 바꿔 줘"))
            .andExpect(status().is3xxRedirection())
            .andExpect(flash().attribute("aiError",containsString("OpenAI 연결 설정")));
        mvc.perform(post(url).session(session).with(csrf()).param("request","다시"))
            .andExpect(flash().attribute("aiError",containsString("잠시 후")));
        session.removeAttribute("itineraryAiRevisionNext");
        when(ai.configured()).thenReturn(true);
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("좀 바꿔 줘"),any()))
            .thenReturn(new OpenAiRevisionClient.Proposal(true,"어느 관광지를 바꿀까요?",List.of(),List.of(),null,null,null,null));
        mvc.perform(post(url).session(session).with(csrf()).param("request","좀 바꿔 줘"))
            .andExpect(flash().attribute("aiQuestion","어느 관광지를 바꿀까요?"));
        // 식사처럼 바꿀 수 없는 요청은 되묻지 않고 할 수 있는 것을 안내한다. 새 수정안은 만들지 않는다.
        session.removeAttribute("itineraryAiRevisionNext");
        when(ai.propose(any(),any(),any(),any(),any(),any(),eq("점심 식당 바꿔 줘"),any()))
            .thenReturn(new OpenAiRevisionClient.Proposal(false,OpenAiRevisionClient.UNSUPPORTED_MESSAGE,List.of(),List.of(),null,null,null,null,true));
        long before=ownJobs();
        var flashed=mvc.perform(post(url).session(session).with(csrf()).param("request","점심 식당 바꿔 줘"))
            .andExpect(flash().attribute("aiNotice",OpenAiRevisionClient.UNSUPPORTED_MESSAGE))
            .andExpect(flash().attributeCount(2)).andReturn().getFlashMap();
        assertThat(ownJobs()).isEqualTo(before);
        // 앞선 요청들이 세션에 남긴 flash 값이 먼저 읽히지 않게 비우고, 이번 요청의 값만 넘긴다.
        session.removeAttribute(org.springframework.web.servlet.support.SessionFlashMapManager.class.getName()+".FLASH_MAPS");
        mvc.perform(get("/travel/recommendations/itineraries/"+original).session(session).flashAttrs(flashed))
            .andExpect(content().string(containsString("바꿀 수 없는 요청이에요")))
            .andExpect(content().string(containsString("일정 수정하기")));
    }

    @Test void timeOnlyRevisionKeepsEveryPlace() {
        var original=completedDraft();
        var revised=schedules.revise(original,null,hash,List.of(),null,LocalTime.of(8,0),LocalTime.of(20,0),120);
        assertThat(attractionIds(revised)).containsExactlyElementsOf(attractionIds(original));
        assertThat(schedules.view(revised,null,hash).input().revision().replaced()).isEmpty();
        // 숙소를 고르지 않은 수정안은 처음 초안의 숙소를 그대로 이어받는다(시간만 바꿨는데 숙소가 바뀌지 않게).
        Long stay=schedules.view(original,null,hash).result().lodging().chosenPlaceId();
        assertThat(stay).isNotNull();
        assertThat(schedules.view(revised,null,hash).input().choices().stayPlaceId()).isEqualTo(stay);
        finish(revised);
        assertThat(schedules.view(revised,null,hash).result().lodging().chosenPlaceId()).isEqualTo(stay);
    }

    /** 날짜별 방문·식사 장소를 순서대로 모은다. 시각은 빼서 장소와 순서만 비교한다. */
    private List<List<Long>> stops(UUID id,long removed) {
        return schedules.view(id,null,hash).result().days().stream().map(day->day.items().stream()
            .filter(i->List.of("VISIT","MEAL","STAY").contains(i.type()) && i.placeId()!=removed).map(i->i.placeId()).toList()).toList();
    }

    // 관광지 하나를 빼도 나머지 관광지·식당·숙소의 날짜와 순서는 그대로다(2026-11-02). 예전에는 일정을 처음부터 다시 배치해 다른 곳까지 바뀌었다.
    @Test void removingOnePlaceKeepsTheRestOfTheItinerary() {
        var original=completedDraft();
        var result=schedules.view(original,null,hash).result();
        var visits=result.days().getFirst().items().stream().filter(i->"VISIT".equals(i.type())).toList();
        assertThat(visits.size()).isGreaterThan(1);
        long removed=visits.getFirst().placeId();

        var revised=schedules.reviseDirect(original,null,hash,new ItineraryJobService.DirectChange(List.of(removed),null,null,null,null,null,null,null));
        var revision=schedules.view(revised,null,hash).input().revision();
        assertThat(revision.replaced()).extracting(PlaceRef::placeId).containsExactly(removed);
        assertThat(revision.keptDays()).hasSize(result.days().size());
        finish(revised);

        assertThat(stops(revised,removed)).isEqualTo(stops(original,removed));
        var after=schedules.view(revised,null,hash).result();
        assertThat(after.days().stream().flatMap(d->d.items().stream()).map(i->i.placeId())).doesNotContain(removed);
    }

    // 그날의 관광지를 모두 빼면 다른 날에서 옮겨 와야 하므로 예전처럼 다시 배치한다.
    @Test void removingEveryPlaceOfADayArrangesAgain() {
        var original=completedDraft();
        var firstDay=schedules.view(original,null,hash).result().days().getFirst().items().stream()
            .filter(i->"VISIT".equals(i.type())).map(i->i.placeId()).toList();
        var revised=schedules.reviseDirect(original,null,hash,new ItineraryJobService.DirectChange(firstDay,null,null,null,null,null,null,null));
        assertThat(schedules.view(revised,null,hash).input().revision().keptDays()).isNull();
    }

    // 시간을 함께 바꾸면 빼기만 한 수정안이 아니므로 예전처럼 다시 배치한다.
    @Test void removingWithAnotherChangeArrangesAgain() {
        var original=completedDraft();
        long removed=attractionIds(original).getFirst();
        var revised=schedules.reviseDirect(original,null,hash,new ItineraryJobService.DirectChange(List.of(removed),null,null,null,null,LocalTime.of(10,0),null,null));
        assertThat(schedules.view(revised,null,hash).input().revision().keptDays()).isNull();
    }

    // 직접 수정 화면이 열리고, 화면의 '빼기'로 수정안이 만들어지며, 다른 세션은 볼 수 없다.
    @Test void directEditScreenOpensAndRemoveCreatesRevision() throws Exception {
        var original=completedDraft();
        long removed=attractionIds(original).getFirst();
        mvc.perform(get("/travel/recommendations/itineraries/"+original+"/edit").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("강릉 일정 수정")))
            .andExpect(content().string(containsString("다른 장소의 날짜와 순서는 그대로")));
        mvc.perform(get("/travel/recommendations/itineraries/"+original+"/edit").session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(post("/travel/recommendations/itineraries/"+original+"/direct-revisions").session(session).with(csrf()).param("remove",Long.toString(removed)))
            .andExpect(status().is3xxRedirection());
    }

    @Test void invalidRequestsDoNotCreateJobs() {
        var original=completedDraft();
        var ids=attractionIds(original);
        long count=ownJobs();
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
        assertThat(ownJobs()).isEqualTo(count+1);
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
        // 만들지 못하면 오류 화면으로 가지 않고 보던 초안의 '일정 수정하기' 절에 이유를 보여 준다.
        mvc.perform(post(url).session(session).with(csrf()).param("replace",ids.getFirst().toString())
                .param("start","09:00").param("end","18:00").param("visitMinutes","90"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location","/travel/recommendations/itineraries/"+original+"#revise-title"))
            .andExpect(flash().attribute("reviseError",containsString("교체 이유를 골라 주세요.")));
        // 직접 수정 화면에서 보낸 요청이면 그 화면으로 돌아간다.
        mvc.perform(post(url).session(session).with(csrf()).param("replace",ids.getFirst().toString()).param("fromEdit","true")
                .param("start","09:00").param("end","18:00").param("visitMinutes","90"))
            .andExpect(status().isSeeOther())
            .andExpect(header().string("Location","/travel/recommendations/itineraries/"+original+"/edit"))
            .andExpect(flash().attribute("editError",containsString("교체 이유를 골라 주세요.")));
        // 시간만 바꾸기에서 지금과 같은 값을 보내면 수정안을 만들지 않고, 입력한 값을 다시 채울 수 있게 돌려준다.
        long count=ownJobs();
        mvc.perform(post(url).session(session).with(csrf()).param("start","09:00").param("end","15:00").param("visitMinutes","90"))
            .andExpect(status().isSeeOther())
            .andExpect(flash().attribute("reviseError",containsString("바꾼 내용이 없어요")))
            .andExpect(flash().attributeExists("timeValues"));
        assertThat(ownJobs()).isEqualTo(count);
        mvc.perform(get("/travel/recommendations/itineraries/"+original).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("AI 없이 시간만 바꾸기")));
    }

    private String draftUrl(UUID id) { return "/travel/recommendations/itineraries/"+id; }
    // 직접 수정을 보내고, 실패했을 때 수정 화면에 보여 줄 문장을 확인한다. 초안은 새로 생기지 않는다.
    private void directChangeIsRefused(UUID id,String param,String value,String reason) throws Exception {
        long count=ownJobs();
        var request=post(draftUrl(id)+"/direct-revisions").session(session).with(csrf());
        if(param!=null) request.param(param,value);
        mvc.perform(request).andExpect(redirectedUrl(draftUrl(id)+"/edit"))
            .andExpect(flash().attribute("editError",containsString(reason)));
        assertThat(ownJobs()).isEqualTo(count);
    }

    @Test void editScreenOpensOnlyForTheSameSession() throws Exception {
        var draft=completedDraft();
        mvc.perform(get(draftUrl(draft)+"/edit").session(session)).andExpect(status().isOk()).andExpect(view().name("travel/itinerary-edit"))
            .andExpect(content().string(containsString("일정")));
        mvc.perform(get(draftUrl(draft)+"/edit").session(new MockHttpSession())).andExpect(status().isNotFound());
        mvc.perform(post(draftUrl(draft)+"/direct-revisions").session(new MockHttpSession()).with(csrf()).param("visitMinutes","60"))
            .andExpect(status().isNotFound());
    }

    @Test void directRevisionRemovesOnlyTheChosenPlaceAndKeepsTheOriginal() throws Exception {
        var original=completedDraft();
        var before=schedules.view(original,null,hash).result();
        Long removed=attractionIds(original).getFirst();
        String location=mvc.perform(post(draftUrl(original)+"/direct-revisions").session(session).with(csrf()).param("remove",removed.toString()))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var revision=UUID.fromString(location.substring(location.lastIndexOf('/')+1));
        assertThat(revision).isNotEqualTo(original);
        finish(revision);
        var changed=schedules.view(revision,null,hash).result().days().stream().flatMap(d->d.items().stream()).filter(i->"VISIT".equals(i.type())).map(ItineraryPlan.Item::placeId).toList();
        assertThat(changed).doesNotContain(removed);
        assertThat(schedules.view(original,null,hash).result()).isEqualTo(before);
    }

    @Test void directRevisionRefusesValuesOutsideTheDraft() throws Exception {
        var draft=completedDraft();
        directChangeIsRefused(draft,"remove","999999999","이 일정에 있는 관광지만 뺄 수 있어요");
        directChangeIsRefused(draft,"avoidRestaurant","999999999","이 일정에 있는 식당만 바꿀 수 있어요");
        directChangeIsRefused(draft,"stay","999999999","이 일정의 숙소 후보 중에서 골라 주세요");
        directChangeIsRefused(draft,null,null,"바꾼 내용이 없어요");
    }

    @Test void directRevisionCannotRemoveARequiredPlace() throws Exception {
        Long required=attractionIds(completedDraft()).getFirst();
        var draft=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(16,0),KakaoMapClient.Transport.CAR,90,
            new ItineraryPlan.Constraints(List.of(required),null,false,null,List.of(),null,null),null,null,null,null);
        finish(draft);
        directChangeIsRefused(draft,"remove",required.toString(),"필수 방문 장소는 뺄 수 없어요");
    }

    // AI 확인 검색은 유료 호출이다. 찾을 항목이 없으면 부르지 않고, 저장한 결과가 있으면 다시 부르지 않으며, 검색 중 초안이 바뀌면 붙이지 않는다.
    @Test void verificationIsReusedAndRefusedWhenTheDraftChanged() throws Exception {
        when(verifier.configured()).thenReturn(true);
        var draft=completedDraft();
        assertThatThrownBy(()->schedules.lookupVerifications(draft,null,hash))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("찾아볼 확인 필요 항목이 없어요");
        var saved=new ItineraryPlan.Verifications(List.of(),Instant.now());
        String resultJson=jobs.findById(draft).orElseThrow().getResultJson();
        assertThatThrownBy(()->schedules.saveVerifications(draft,null,hash,"{}",saved))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("일정이 바뀌었어요");
        schedules.saveVerifications(draft,null,hash,resultJson,saved);
        em.flush(); em.clear();
        assertThat(schedules.lookupVerifications(draft,null,hash).checkedAt()).isEqualTo(saved.checkedAt());
        verify(verifier,never()).lookup(any());
    }

    // 버스·비행기 안내와 실제 경로는 세션마다 10초에 한 번 받고, 다른 세션의 초안은 없는 것처럼 답한다.
    @Test void guidesWaitTenSecondsAndAreHiddenFromOtherSessions() throws Exception {
        var draft=completedDraft();
        var bus=draftUrl(draft)+"/bus-guide";
        mvc.perform(post(bus).session(session).with(csrf()).param("to","강릉").param("from","동서울")).andExpect(status().is3xxRedirection());
        mvc.perform(post(bus).session(session).with(csrf()).param("to","강릉").param("from","동서울"))
            .andExpect(flash().attribute("busError",containsString("방금 조회했어요")));
        mvc.perform(post(bus).session(new MockHttpSession()).with(csrf()).param("to","강릉").param("from","동서울")).andExpect(status().isNotFound());
        var flight=draftUrl(draft)+"/flight-guide";
        mvc.perform(post(flight).session(session).with(csrf())).andExpect(status().is3xxRedirection());
        mvc.perform(post(flight).session(session).with(csrf())).andExpect(flash().attribute("flightError",containsString("방금 조회했어요")));
        mvc.perform(post(flight).session(new MockHttpSession()).with(csrf())).andExpect(status().isNotFound());
    }

    @Test void dayPathsWorkOnlyForTransitDraftsAndWaitTenSeconds() throws Exception {
        mvc.perform(get(draftUrl(completedDraft())+"/day-paths").session(session).param("day","1")).andExpect(status().isBadRequest());
        var transit=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(16,0),KakaoMapClient.Transport.PUBLIC_TRANSIT,90);
        finish(transit);
        var url=draftUrl(transit)+"/day-paths";
        mvc.perform(get(url).session(new MockHttpSession()).param("day","1")).andExpect(status().isNotFound());
        mvc.perform(get(url).session(session).param("day","1")).andExpect(status().isOk());
        mvc.perform(get(url).session(session).param("day","1")).andExpect(status().isTooManyRequests())
            .andExpect(content().string(containsString("방금 경로를 찾았어요")));
    }

    // 대중교통 안내는 찾기 한 번으로 버스와 기차를 함께 찾고, 화면에서는 버스·기차 탭으로 나눠 보여 준다.
    @Test void transitGuideShowsBusAndTrainTabs() throws Exception {
        var transit=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(16,0),KakaoMapClient.Transport.PUBLIC_TRANSIT,90);
        finish(transit);
        // 여행지로 가는데 출발지를 비우면 두 안내 모두 출발지를 물어본다(외부 조회 없음).
        var flashed=mvc.perform(post(draftUrl(transit)+"/bus-guide").session(session).with(csrf()).param("to","강릉"))
            .andExpect(flash().attribute("busError",containsString("출발 도시나 터미널")))
            .andExpect(flash().attribute("trainError",containsString("출발 도시나 역")))
            .andReturn().getFlashMap();
        session.removeAttribute(org.springframework.web.servlet.support.SessionFlashMapManager.class.getName()+".FLASH_MAPS");
        mvc.perform(get(draftUrl(transit)).session(session).flashAttrs(flashed))
            .andExpect(content().string(containsString("대중교통으로 가는 방법")))
            .andExpect(content().string(containsString("data-transit-tabs")))
            .andExpect(content().string(containsString("출발 도시나 역 이름을 적거나")));
        // 기차 안내는 열차 종류별 요약으로 보여 주고, 여행 날짜 시간표가 없으면 오늘 기준이라고 알린다.
        var today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        var guide=new com.travel.travel_recommendation.dto.TrainGuide("서울역","강릉","서울역","강릉역",today,today.plusDays(30),
            List.of(new com.travel.travel_recommendation.dto.TrainGuide.Route("KTX-이음",18,"05:06","22:10",114,27600)),null);
        mvc.perform(get(draftUrl(transit)).session(session).flashAttr("trainGuide",guide))
            .andExpect(content().string(containsString("서울역 → 강릉역")))
            .andExpect(content().string(containsString("하루 18회 · 첫차 05:06 · 막차 22:10 · 가장 빠른 소요 약 114분 · 어른 27,600원부터")))
            .andExpect(content().string(containsString("오늘 기준으로 보여 드려요")));
    }
}
