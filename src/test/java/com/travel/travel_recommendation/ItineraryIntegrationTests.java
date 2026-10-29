package com.travel.travel_recommendation;

import com.travel.travel_recommendation.service.KoreaTime;
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
    // 장소 수집(refresh)이 실제 관광공사·GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 빈 목록을 돌려준다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    @MockitoBean OpenAiPriceClient prices;
    @Autowired PlaceOperatingService operating;
    @Autowired PlacePhotoService photos;
    @Autowired NearbyRestaurantService nearbyRestaurants;
    @Autowired PlaceRepository placeRepository;
    @Autowired com.travel.travel_recommendation.repository.UserRepository users;
    @Autowired com.travel.travel_recommendation.service.SavedItineraryService saved;
    @Autowired com.travel.travel_recommendation.repository.ItineraryRepository itineraryRows;
    private MockHttpSession session;
    private byte[] hash;
    private UUID parent;
    private long destination;
    @BeforeEach void setup() throws Exception {
        ExternalPlaceCache.expireExisting(jdbc);
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

    @Test void missingPeriodAndStayAreCollectedAtScheduleWithoutChangingRecommendation() throws Exception {
        var conditions=new ConfirmedTravelConditions("TEXT","상어 보고 싶어","강릉",null,null,null,null,null,null,null,List.of(),"")
            .withPurpose("상어 관람");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidate=recommendations.view(parent,null,hash).candidates().getFirst(); destination=candidate.placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"추천"))));
        em.flush();
        var page=mvc.perform(get(formUrl()).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("여행 일수 (필수)"))).andReturn();
        String token=(String)page.getModelAndView().getModel().get("submissionToken");
        var response=mvc.perform(post(formUrl()).session(session).with(csrf()).param("submissionToken",token)
            .param("start","09:00").param("end","18:00").param("transport","CAR").param("visitMinutes","90")
            .param("durationDays","2").param("accommodationNeeded","true"))
            .andExpect(status().isSeeOther()).andReturn().getResponse();
        UUID id=UUID.fromString(response.getHeader("Location").replace("/travel/recommendations/itineraries/",""));
        var input=schedules.view(id,null,hash).input();
        assertThat(input.conditions().durationDays()).isEqualTo(2);
        assertThat(input.conditions().travelPurpose()).isEqualTo("상어 관람");
        assertThat(input.places().accommodations()).isNotEmpty();
        assertThat(recommendations.conditions(parent,null,hash).durationDays()).isNull();
        var work=schedules.claim(id); schedules.complete(work,schedules.generate(work));
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
        // 수정안도 부모 추천(기간 없음)이 아니라 이 초안의 기간·숙박 조건으로 만든다.
        var revised=schedules.revise(id,null,hash,List.of(),null,LocalTime.of(10,0),LocalTime.of(18,0),90);
        var revisedInput=schedules.view(revised,null,hash).input();
        assertThat(revisedInput.conditions().durationDays()).isEqualTo(2);
        assertThat(revisedInput.conditions().accommodationNeeded()).isTrue();
        assertThat(revisedInput.places().accommodations()).isNotEmpty();
    }
    @Test void quickScheduleUsesDefaultsOrAsksOnlyForMissingInfo() throws Exception {
        String quickUrl="/travel/recommendations/"+parent+"/destinations/"+destination+"/quick-schedule";
        String infoUrl="/travel/recommendations/"+parent+"/destinations/"+destination+"/trip-info";
        // 기본 추천은 출발일·이동수단이 정해지지 않아 추측하지 않고, 빠진 값만 묻는 여행 정보 화면으로 보낸다.
        mvc.perform(post(quickUrl).session(session).with(csrf()))
            .andExpect(status().isSeeOther()).andExpect(header().string("Location",infoUrl));
        mvc.perform(get(infoUrl).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("출발일 (필수)"))).andExpect(content().string(containsString("이동수단 (필수)")))
            // 집 출발·귀가 시각과 편도 시간은 입력받지 않는다(09:00 출발·20:00 도착, 이동시간은 자동).
            .andExpect(content().string(containsString("출발지 (선택")))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"departAt\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("name=\"homeMinutes\""))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("여행 일수 (필수)"))))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("반드시 지킬 조건"))));
        // 지난 날짜는 되돌려 보내고 이유를 알린다.
        mvc.perform(post(quickUrl).session(session).with(csrf()).param("startDate",LocalDate.now(ZoneId.of("Asia/Seoul")).minusDays(1).toString()).param("transport","CAR"))
            .andExpect(header().string("Location",infoUrl));
        mvc.perform(get(infoUrl).session(session)).andExpect(content().string(containsString("출발일은 오늘이나 그 이후")));
        // 비회원이 출발지 칸을 아직 보지 않았으면(출발지 없이 보냄) 여행 정보 화면에서 한 번 묻는다.
        LocalDate chosen=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(15);
        mvc.perform(post(quickUrl).session(session).with(csrf()).param("startDate",chosen.toString()).param("transport","PUBLIC_TRANSIT"))
            .andExpect(header().string("Location",infoUrl));
        mvc.perform(get(infoUrl).session(session)).andExpect(content().string(containsString("출발지를 적으면 집에서 오가는 이동까지")));
        // 출발지를 비워 보내면 집 이동 없이 여행지 도착(하루 시작 09:00)부터 기본 시간 초안을 만든다.
        String made=mvc.perform(post(quickUrl).session(session).with(csrf()).param("startDate",chosen.toString()).param("transport","PUBLIC_TRANSIT")
                .param("origin","").param("returnPlace",""))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        var madeInput=schedules.view(UUID.fromString(made.replace("/travel/recommendations/itineraries/","")),null,hash).input();
        assertThat(madeInput.conditions().startDate()).isEqualTo(chosen);
        assertThat(madeInput.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(madeInput.start()).isEqualTo(LocalTime.of(9,0));
        assertThat(madeInput.home()).isNull();
        assertThat(madeInput.dayStart(1)).isEqualTo(LocalTime.of(9,0));

        var car=new TravelKeyword(null,"TRANSPORTATION","자동차","{\"value\":\"CAR\"}");
        LocalDate start=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(20);
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(car),"");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidate=recommendations.view(parent,null,hash).candidates().getFirst(); destination=candidate.placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"추천"))));
        em.flush();
        quickUrl="/travel/recommendations/"+parent+"/destinations/"+destination+"/quick-schedule";
        mvc.perform(post(quickUrl).session(session)).andExpect(status().isForbidden());
        // 여행 조건이 모두 있어도 집 출발·귀가는 추천 조건에 없어 한 번 묻는다.
        mvc.perform(post(quickUrl).session(session).with(csrf())).andExpect(header().string("Location",
            "/travel/recommendations/"+parent+"/destinations/"+destination+"/trip-info"));
        // 출발지를 적으면 09:00 출발·20:00 도착으로 정하고 이동시간은 카카오 길찾기로 찾는다(테스트에서는 5분).
        // 이동시간은 관광지 가운데까지 찾으므로, 좌표가 없는 시스템 관광지에 이 테스트에서만 좌표를 넣는다.
        withAttractionCoordinates(destination);
        when(maps.findOrigin("서울 송파구")).thenReturn(new KakaoMapClient.Origin("서울특별시 송파구","서울",
            new KakaoMapClient.Location("서울특별시 송파구",127.1,37.5,Instant.now())));
        String location=mvc.perform(post(quickUrl).session(session).with(csrf()).param("origin","서울 송파구"))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        assertThat(location).startsWith("/travel/recommendations/itineraries/");
        var input=schedules.view(UUID.fromString(location.replace("/travel/recommendations/itineraries/","")),null,hash).input();
        assertThat(input.conditions().startDate()).isEqualTo(start);
        assertThat(input.conditions().durationDays()).isEqualTo(2);
        assertThat(input.home().departAt()).isEqualTo(LocalTime.of(9,0));
        assertThat(input.home().arriveBy()).isEqualTo(LocalTime.of(20,0));
        assertThat(input.home().origin()).isEqualTo("서울특별시 송파구");
        // 다시 눌러도 새로 만들지 않고 같은 초안으로 보낸다.
        mvc.perform(post(quickUrl).session(session).with(csrf()).param("origin","서울 송파구"))
            .andExpect(header().string("Location",location));
    }
    private void withAttractionCoordinates(long destinationId) {
        var region=placeRepository.findById(destinationId).orElseThrow().getRegion();
        jdbc.update("UPDATE dbo.places SET latitude=37.7, longitude=128.9 WHERE place_type='ATTRACTION' AND region=?",region);
        em.flush(); em.clear();
    }
    private String formUrl() { return "/travel/recommendations/"+parent+"/destinations/"+destination+"/schedule"; }

    // 회원이 내 정보에 적은 '주로 출발하는 곳'을 일정 입력의 출발지 칸에 미리 넣는다. 적지 않은 회원·비회원은 빈 칸이다.
    @Test void memberHomeAreaPrefillsTheOrigin() throws Exception {
        String suffix=UUID.randomUUID().toString().replace("-","");
        var member=users.saveAndFlush(new com.travel.travel_recommendation.entity.User("test_"+suffix,"$2a$10$notusedhashnotusedhashnotusedhashnotusedhashnotuse","여행_"+suffix));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,2,null,2,true,false,List.of(),"");
        UUID owned=recommendations.create(conditions,member.getLoginId(),null); recommendations.prepare(owned);
        var candidate=recommendations.view(owned,member.getLoginId(),null).candidates().getFirst();
        jobs.findById(owned).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"추천"))));
        em.flush();
        String info="/travel/recommendations/"+owned+"/destinations/"+candidate.placeId()+"/trip-info";
        String form="/travel/recommendations/"+owned+"/destinations/"+candidate.placeId()+"/schedule";
        var auth=org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user(member.getLoginId());
        // 아직 적지 않은 회원에게는 내 정보에 적어 두라고 안내한다.
        mvc.perform(get(info).with(auth)).andExpect(content().string(containsString("내 정보에 '주로 출발하는 곳'을 적어 두면")));
        member.changeHomeArea("서울 송파구"); users.saveAndFlush(member);
        // '일정 만들기'를 처음 누르면(아무 값 없이) 여행 정보 화면으로 넘어오는데, 이때도 출발지를 미리 넣는다.
        var memberSession=new MockHttpSession();
        mvc.perform(post("/travel/recommendations/"+owned+"/destinations/"+candidate.placeId()+"/quick-schedule").session(memberSession).with(auth).with(csrf()))
            .andExpect(header().string("Location",info));
        mvc.perform(get(info).session(memberSession).with(auth)).andExpect(content().string(containsString("value=\"서울 송파구\"")));
        for(String url:List.of(info,form))
            mvc.perform(get(url).with(auth)).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"서울 송파구\"")))
                .andExpect(content().string(containsString("내 정보의 '주로 출발하는 곳'을 넣어 두었어요")));
        // 조건이 모두 있는 추천이면, 출발지를 적어 둔 회원은 여행 정보 화면 없이 그 출발지로 바로 초안을 만든다.
        withAttractionCoordinates(candidate.placeId());
        when(maps.findOrigin("서울 송파구")).thenReturn(new KakaoMapClient.Origin("서울특별시 송파구","서울",
            new KakaoMapClient.Location("서울특별시 송파구",127.1,37.5,Instant.now())));
        var car=new TravelKeyword(null,"TRANSPORTATION","자동차","{\"value\":\"CAR\"}");
        LocalDate start=LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(20);
        UUID full=recommendations.create(new ConfirmedTravelConditions("KEYWORD","","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(car),""),
            member.getLoginId(),null);
        recommendations.prepare(full);
        var fullCandidate=recommendations.view(full,member.getLoginId(),null).candidates().getFirst();
        jobs.findById(full).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,fullCandidate,"추천"))));
        em.flush();
        String direct=mvc.perform(post("/travel/recommendations/"+full+"/destinations/"+fullCandidate.placeId()+"/quick-schedule")
                .session(new MockHttpSession()).with(auth).with(csrf()))
            .andExpect(status().isSeeOther()).andReturn().getResponse().getHeader("Location");
        assertThat(direct).startsWith("/travel/recommendations/itineraries/");
        var directHome=schedules.view(UUID.fromString(direct.replace("/travel/recommendations/itineraries/","")),member.getLoginId(),null).input().home();
        assertThat(directHome.origin()).isEqualTo("서울특별시 송파구");
        assertThat(directHome.departAt()).isEqualTo(LocalTime.of(9,0));
        // 비회원 화면에는 회원 안내가 없다.
        mvc.perform(get("/travel/recommendations/"+parent+"/destinations/"+destination+"/trip-info").session(session))
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("주로 출발하는 곳"))));
    }

    // 일정 작업 전에 고른 관광지 주변의 음식점을 찾아 저장하고 요청에 더한다. 그 요청으로 만든 일정은 그대로 검증을 통과한다.
    @Test void nearbyRestaurantsAreAddedBeforeTheScheduleIsMade() {
        var region=placeRepository.findById(destination).orElseThrow().getRegion();
        // 시스템 관광지에는 좌표가 없어 주변을 찾을 지점이 없으므로, 이 테스트에서만 좌표를 넣는다.
        jdbc.update("UPDATE dbo.places SET latitude=37.7, longitude=128.9 WHERE place_type='ATTRACTION' AND region=?",region);
        em.flush(); em.clear();
        var checked=KoreaTime.now().withNano(0);
        var nearby=new KakaoPlace("990000001","RESTAURANT","주변테스트식당",region,region+" 테스트로 1","음식점 > 한식 > 국수",
            128.901,37.701,"https://place.map.kakao.com/990000001",checked);
        when(maps.searchRestaurantsNear(anyDouble(),anyDouble(),eq(region),anyInt(),anyInt())).thenReturn(List.of(nearby));
        UUID id=create();
        int before=schedules.view(id,null,hash).input().places().restaurants().size();
        nearbyRestaurants.enrich(id);
        var input=schedules.view(id,null,hash).input();
        assertThat(input.places().restaurants()).hasSize(before+1);
        assertThat(input.places().restaurants().getLast().name()).isEqualTo("주변테스트식당");
        // 다시 실행해도 같은 음식점을 또 더하지 않는다.
        nearbyRestaurants.enrich(id);
        assertThat(schedules.view(id,null,hash).input()).isEqualTo(input);
        var work=schedules.claim(id); schedules.complete(work,schedules.generate(work));
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
    }
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
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("명절 부분 휴관")))
            // 완성된 비회원 초안에는 저장 전에 세션 만료 시간을 알린다.
            .andExpect(content().string(containsString("30분 동안 아무 동작이 없거나 브라우저를 닫으면 다시 열 수 없어요")));
        var second=create(); var secondWork=schedules.claim(second); var secondResult=schedules.generate(secondWork);
        placeRepository.findById(candidate.placeId()).orElseThrow().updateDetails("{}"); em.flush();
        schedules.complete(secondWork,secondResult);
        assertThat(schedules.view(second,null,hash).status()).isEqualTo("FAILED");
        assertThatThrownBy(()->operating.refresh(parent,destination,candidate.placeId(),null,new byte[32]))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        verify(tour,times(1)).search(candidate.name());
    }

    // 참고 가격은 완성된 초안에 붙기만 하고 일정은 바꾸지 않는다. 24시간 안에 다시 찾으면 유료 검색을 다시 하지 않는다.
    @Test void priceLookupAttachesQuotesWithoutChangingScheduleAndReusesThem() throws Exception {
        var id=create(); var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush();
        var before=schedules.view(id,null,hash).result();
        when(prices.configured()).thenReturn(true);
        when(prices.lookup(any(),eq(OpenAiPriceClient.LODGING))).thenAnswer(call->{ PlaceCandidate p=call.getArgument(0);
            return new ItineraryPlan.PriceQuote(p.placeId(),p.name(),OpenAiPriceClient.LODGING,"UNVERIFIED","1박 12만~15만원",120000,150000,"스탠다드 더블 1박","https://booking.example.kr/"+p.placeId()); });
        when(prices.lookup(any(),eq(OpenAiPriceClient.RESTAURANT))).thenAnswer(call->{ PlaceCandidate p=call.getArgument(0);
            return new ItineraryPlan.PriceQuote(p.placeId(),p.name(),OpenAiPriceClient.RESTAURANT,"VERIFIED","대표 메뉴 1만~1만2천원",10000,12000,"대표 메뉴 1인분","https://menu.example.kr/"+p.placeId()); });
        mvc.perform(post(resultUrl(id)+"/prices").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        em.flush(); em.clear();
        var after=schedules.view(id,null,hash).result();
        assertThat(after.days()).isEqualTo(before.days()); assertThat(after.lodging()).isEqualTo(before.lodging());
        assertThat(after.prices().quotes()).isNotEmpty();
        assertThat(after.prices().quotes().getFirst().kind()).isEqualTo(OpenAiPriceClient.LODGING);
        int calls=after.prices().quotes().size();
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isOk())
            // 2026-10-10부터 참고 합계 대신 항목별 예상 경비 절에 찾은 가격을 반영한다.
            .andExpect(content().string(containsString("예상 경비 (추정)")))
            .andExpect(content().string(containsString("찾은 가격은 위 예상 경비의 숙박·식비에 반영돼요")))
            .andExpect(content().string(containsString("출처 페이지에서 상호·가격 확인")))
            .andExpect(content().string(containsString("AI 검색 결과, 출처에서 직접 확인 필요")))
            .andDo(response->{
                if(Boolean.getBoolean("travel.ui.capture")) {
                    var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
                    java.nio.file.Files.writeString(folder.resolve("itinerary-prices.html"),response.getResponse().getContentAsString(StandardCharsets.UTF_8));
                }
            });
        assertThat(schedules.lookupPrices(id,null,hash)).isEqualTo(after.prices());
        verify(prices,times(calls)).lookup(any(),any());
        // 다른 세션은 남의 초안 가격을 찾을 수 없다.
        assertThatThrownBy(()->schedules.lookupPrices(id,null,new byte[32])).isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    private void answerPrices() {
        when(prices.configured()).thenReturn(true);
        when(prices.lookup(any(),anyString())).thenAnswer(call->{ PlaceCandidate p=call.getArgument(0); String kind=call.getArgument(1);
            return new ItineraryPlan.PriceQuote(p.placeId(),p.name(),kind,"VERIFIED","참고 가격",20000,30000,"기준","https://price.example.kr/"+p.placeId()); });
    }
    // 일정에 들어간 관광지의 관광공사 사진을 한 번 찾아 저장하고, 초안 화면에 출처와 함께 보여 준다. 다음에는 다시 검색하지 않는다.
    @Test void draftAttractionPhotosAreFoundOnceAndShownWithCredit() throws Exception {
        var id=create(); var work=schedules.claim(id); var result=schedules.generate(work);
        var visit=result.days().stream().flatMap(d->d.items().stream()).filter(i->"VISIT".equals(i.type())).findFirst().orElseThrow();
        var candidate=work.input().attractionsWithReserve().stream().filter(p->p.placeId().equals(visit.placeId())).findFirst().orElseThrow();
        String image="https://tong.visitkorea.or.kr/cms/resource/test_photo.jpg";
        when(tour.configured()).thenReturn(true);
        when(tour.searchPhotos(anyString())).thenReturn(List.of());
        when(tour.searchPhotos(candidate.name())).thenReturn(List.of(new TourApiClient.SearchPlace("1","12",candidate.name(),candidate.address(),
            candidate.longitude()==null?128.9:candidate.longitude(),candidate.latitude()==null?37.7:candidate.latitude(),image,"Type1")));
        photos.findForItinerary(work.input(),result,Instant.now().plusSeconds(15));
        schedules.complete(work,result); em.flush();
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
        assertThat(photos.photos(List.of(visit.placeId())).get(visit.placeId()).url()).isEqualTo(image);
        mvc.perform(get(resultUrl(id)).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString(image))).andExpect(content().string(containsString("사진: 한국관광공사")));
        clearInvocations(tour);
        photos.findForItinerary(work.input(),result,Instant.now().plusSeconds(15));
        verify(tour,never()).searchPhotos(candidate.name());
    }
    private UUID completedDraft() {
        var id=create(); var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush(); return id;
    }

    // 다른 초안이 같은 숙소·음식점을 쓰면 장소에 보관한 가격을 다시 써서 유료 검색을 하지 않는다.
    @Test void storedPlacePricesAreReusedByAnotherDraft() throws Exception {
        answerPrices();
        var first=schedules.lookupPrices(completedDraft(),null,hash); em.flush(); em.clear();
        int searched=first.quotes().size();
        assertThat(searched).isPositive();
        assertThat(first.quotes()).allSatisfy(q->assertThat(q.checkedAt()).isNotNull());
        var place=placeRepository.findById(first.quotes().getFirst().placeId()).orElseThrow();
        assertThat(place.getDetailsJson()).contains("referencePrice");
        // 첫 장소의 보관 결과를 2시간 전 검색으로 바꿔 두면 화면에 '이전 결과 재사용' 안내가 붙는다.
        var old=first.quotes().getFirst().checkedAt(first.quotes().getFirst().checkedAt().minus(Duration.ofHours(2)));
        place.updateDetails(PlacePriceDetails.write(place,old,mapper)); em.flush();
        var second=completedDraft();
        var reused=schedules.lookupPrices(second,null,hash);
        verify(prices,times(searched)).lookup(any(),any());
        assertThat(reused.quotes().getFirst()).isEqualTo(old);
        assertThat(reused.quotes().subList(1,searched)).isEqualTo(first.quotes().subList(1,searched));
        assertThat(reused.quotes().getFirst().reusedBefore(reused.checkedAt())).isTrue();
        assertThat(reused.quotes().getLast().reusedBefore(reused.checkedAt())).isEqualTo(searched==1);
        mvc.perform(get(resultUrl(second)).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("이전에 찾은 결과를 다시 사용했어요")));
    }

    // 여러 곳을 찾다 중간에 실패해도 이미 비용을 낸 앞쪽 검색 결과는 장소에 남는다.
    @Test void searchedPricesAreKeptWhenALaterLookupFails() {
        answerPrices();
        when(prices.lookup(any(),eq(OpenAiPriceClient.RESTAURANT))).thenThrow(new RecommendationException("AI_RATE_LIMIT","제한"));
        var id=completedDraft();
        var lodging=schedules.view(id,null,hash).result().lodging();
        assertThat(lodging).isNotNull();
        assertThatThrownBy(()->schedules.lookupPrices(id,null,hash)).isInstanceOf(RecommendationException.class);
        em.clear();
        assertThat(placeRepository.findById(lodging.chosenPlaceId()).orElseThrow().getDetailsJson()).contains("referencePrice");
        assertThat(schedules.view(id,null,hash).result().prices()).isNull();
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
            .andExpect(content().string(containsString("조건 확인: 충족")))
            // 일정에 들어간 관광지마다 그 자리에서 바꾸는 버튼과, 여러 날이면 일차 버튼이 있다.
            .andExpect(content().string(containsString("다른 곳으로 바꾸기"))).andExpect(content().string(containsString("data-day-tabs")))
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
        // 입력 화면은 후보가 적으면 카카오에서 더 찾는다. 아래는 제출이 지도를 부르지 않는지만 본다.
        clearInvocations(maps);
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
    // 로그인 전에 만든 초안은 옮기지 않고, 같은 세션의 비회원 토큰으로 저장한다. 추천 이력과는 연결하지 않는다.
    @Test void guestDraftIsSavedAfterLoginWithoutRecommendationHistory() {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        var member=users.saveAndFlush(new com.travel.travel_recommendation.entity.User("draft_"+suffix,"unused-hash","초안"+suffix));
        var id=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90,
            null,null,null,LocalDate.now(java.time.ZoneId.of("Asia/Seoul")).plusDays(10));
        var work=schedules.claim(id); schedules.complete(work,schedules.generate(work)); em.flush();
        var savedId=saved.save(id,member.getLoginId(),hash).id(); em.flush(); em.clear();
        var row=itineraryRows.findById(savedId).orElseThrow();
        assertThat(row.getUserId()).isEqualTo(member.getId());
        assertThat(row.getRequestId()).isNull();
        assertThat(jobs.findById(id).orElseThrow().getUserId()).isNull();
    }
    @Test void refusesOtherGuestWrongTypeAndExpiredJob() throws Exception {
        var id=create();
        // 세션이 끝난 비회원(새 세션)에게는 세션 만료 가능성을 안내한다.
        mvc.perform(get(resultUrl(id)).session(new MockHttpSession())).andExpect(status().isNotFound())
            .andExpect(content().string(containsString("30분 넘게 아무 동작이 없어 세션이 끝났을 수 있어요")));
        mvc.perform(get(resultUrl(parent)).session(session)).andExpect(status().isNotFound());
        em.flush(); jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSDATETIME()) WHERE job_id=?",id.toString()); em.clear();
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
        em.flush(); jdbc.update("UPDATE dbo.ai_jobs SET started_at=DATEADD(minute,-3,SYSDATETIME()) WHERE job_id=?",id.toString()); em.clear();
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
