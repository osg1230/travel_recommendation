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
class PlaceDiscoveryIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired PlaceCandidateService candidates;
    @Autowired PlaceDiscoveryService discovery;
    @Autowired DestinationProfileService profiles;
    @Autowired ItineraryJobService schedules;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    // 장소 수집(refresh)이 실제 GPT에 대표 관광지를 묻지 않게 한다(모의 객체는 설정 안 됨으로 답한다).
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.OpenAiLandmarkClient landmarks;
    @MockitoBean TourApiClient tour;
    private final String region="강원특별자치도 강릉시";
    private MockHttpSession session; private byte[] hash; private UUID parent; private long destination;
    @BeforeEach void setup() throws Exception {
        ExternalPlaceCache.expireExisting(jdbc);
        session=new MockHttpSession(); String token=UUID.randomUUID().toString(); session.setAttribute("recommendationGuestToken",token);
        hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,3,null,2,true,false,List.of(),"");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidate=recommendations.view(parent,null,hash).candidates().getFirst(); destination=candidate.placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"테스트 추천")))); em.flush();
        // 앱을 실행하면 여행지 행에 실제 대표 관광지 목록이 30일 보관된다. 이 테스트의 모의 응답으로 다시 만들도록 비운다(끝나면 롤백).
        jdbc.update("UPDATE dbo.places SET details_json=NULL WHERE place_id=?",destination); em.clear();
        when(maps.searchPlaces(eq(region),anyString())).thenAnswer(call->{
            String type=call.getArgument(1);
            return switch(type) {
                case "ATTRACTION" -> java.util.stream.IntStream.rangeClosed(1,8).mapToObj(i->data(i,type,"강원 강릉시 테스트로 "+i)).toList();
                case "RESTAURANT" -> List.of(data(20,type,"강원 강릉시 테스트로 20"));
                default -> List.of(data(30,type,"강원 강릉시 테스트로 30"));
            };
        });
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,2000,Instant.now()));
        when(maps.locate(anyString())).thenAnswer(call->new KakaoMapClient.Location(call.getArgument(0),128.88,37.78,Instant.now()));
    }
    private KakaoPlace data(int id,String type,String address) {
        String source="900000000000000"+id;
        return new KakaoPlace(source,type,"조회 장소 "+id,region,address,"관광",128.87+id*.0001,37.77,"https://place.map.kakao.com/"+source,KoreaTime.now().withNano(0));
    }
    private String url() { return "/travel/recommendations/"+parent+"/destinations/"+destination+"/places"; }
    @Test void refreshAddsUpdatesWithoutChangingSystemAndCreatesThreeDaySchedule() throws Exception {
        int system=jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places WHERE source_provider='SYSTEM'",Integer.class);
        assertThat(discovery.refresh(parent,destination,null,hash)).isEqualTo(10);
        var original=places.findBySourceProviderAndSourcePlaceId("KAKAO","9000000000000001").orElseThrow().getId();
        discovery.refresh(parent,destination,null,hash); em.flush();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9000000000000001").orElseThrow().getId()).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places WHERE source_provider='SYSTEM'",Integer.class)).isEqualTo(system);
        var pool=candidates.find(parent,destination,null,hash);
        assertThat(pool.attractions().stream().filter(p->"KAKAO".equals(p.sourceProvider()))).hasSize(8);
        assertThat(schedules.options(parent,destination,null,hash).places().attractions()).hasSize(10);
        var id=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(20,0),KakaoMapClient.Transport.CAR,60);
        // 09:00~20:00, 체류 60분이면 하루 약 5곳으로 추정해 3일 동안 후보 10곳을 모두 고른다.
        assertThat(schedules.view(id,null,hash).input().places().attractions()).hasSize(10);
        var work=schedules.claim(id); var result=schedules.generate(work); schedules.complete(work,result);
        assertThat(schedules.view(id,null,hash).status()).isEqualTo("COMPLETED");
        assertThat(result.days()).hasSize(3);
        mvc.perform(get(url()).session(session)).andExpect(status().isOk()).andExpect(content().string(containsString("카카오 검색")))
            .andExpect(content().string(containsString("조회 장소 1"))).andDo(response->{
                if(Boolean.getBoolean("travel.ui.capture")) {
                    var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
                    java.nio.file.Files.writeString(folder.resolve("discovered-places.html"),response.getResponse().getContentAsString(StandardCharsets.UTF_8));
                }
            });
    }
    @Test void placesPageFetchesMoreOnceWhenCandidatesAreTooFew() throws Exception {
        // 저장된 강릉 후보만으로는 음식점이 모자라, 장소 목록을 처음 열 때 카카오에서 한 번 더 찾아 온다.
        mvc.perform(get(url()).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("카카오에서 10곳을 더 찾아")))
            .andExpect(content().string(containsString("조회 장소 20")));
        verify(maps,times(3)).searchPlaces(eq(region),anyString());
        // 같은 추천·지역은 세션당 한 번만 자동 조회한다.
        mvc.perform(get(url()).session(session)).andExpect(status().isOk());
        verify(maps,times(3)).searchPlaces(eq(region),anyString());
    }
    @Test void placesPageStillOpensWhenAutomaticLookupFails() throws Exception {
        doThrow(new RecommendationException("MAP_AUTH","인증 확인")).when(maps).searchPlaces(region,"RESTAURANT");
        mvc.perform(get(url()).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("장소를 더 찾아보지 못했어요")));
    }
    @Test void failureDoesNotPartiallyPersistAndEmptyResultKeepsExistingPlaces() {
        int count=jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places",Integer.class);
        doThrow(new RecommendationException("MAP_AUTH","인증 확인")).when(maps).searchPlaces(region,"RESTAURANT");
        assertThatThrownBy(()->discovery.refresh(parent,destination,null,hash)).isInstanceOf(RecommendationException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places",Integer.class)).isEqualTo(count);
        doReturn(List.of()).when(maps).searchPlaces(eq(region),anyString());
        assertThat(discovery.refresh(parent,destination,null,hash)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dbo.places",Integer.class)).isEqualTo(count);
    }
    @Test void rejectsWrongGuestAndCsrfBeforeExternalLookupAndThrottlesDuplicates() throws Exception {
        mvc.perform(post(url()+"/refresh").session(new MockHttpSession()).with(csrf())).andExpect(status().isNotFound());
        mvc.perform(post(url()+"/refresh").session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(maps);
        for(int i=0;i<2;i++) mvc.perform(post(url()+"/refresh").session(session).with(csrf())).andExpect(status().is3xxRedirection());
        verify(maps,times(3)).searchPlaces(eq(region),anyString());
    }
    @Test void expiredExternalCacheIsHiddenAndInactivePlacesStayInactive() {
        discovery.refresh(parent,destination,null,hash); em.flush();
        jdbc.update("UPDATE dbo.places SET verified_at=DATEADD(hour,-25,SYSDATETIME()) WHERE source_provider='KAKAO' AND source_place_id=?","9000000000000001");
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE source_provider='KAKAO' AND source_place_id=?","9000000000000002"); em.clear();
        assertThat(candidates.find(parent,destination,null,hash).attractions().stream().filter(p->"KAKAO".equals(p.sourceProvider()))).hasSize(6);
        discovery.refresh(parent,destination,null,hash); em.flush();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9000000000000002").orElseThrow().isActive()).isFalse();
    }
    // 사찰·성당은 관광공사 관광지와 가까운 곳만 받는다(사찰 1km, 성당 300m). 관광공사 조회가 실패하면 그 종류만 빼고 계속한다.
    @Test void religiousPlacesAreAddedAndOnlyTouristCathedralsKept() {
        var temple=new KakaoPlace("9100000000000001","ATTRACTION","테스트사",region,"강원 강릉시 사찰로 1","문화,예술 > 종교 > 불교 > 절,사찰",
            128.80,37.70,"https://place.map.kakao.com/9100000000000001",KoreaTime.now().withNano(0));
        var listed=new KakaoPlace("9100000000000002","ATTRACTION","임당동성당",region,"강원 강릉시 성당로 1","문화,예술 > 종교 > 천주교 > 성당",
            128.90,37.75,"https://place.map.kakao.com/9100000000000002",KoreaTime.now().withNano(0));
        var parish=new KakaoPlace("9100000000000003","ATTRACTION","동네성당",region,"강원 강릉시 성당로 2","문화,예술 > 종교 > 천주교 > 성당",
            128.95,37.80,"https://place.map.kakao.com/9100000000000003",KoreaTime.now().withNano(0));
        var localTemple=new KakaoPlace("9100000000000004","ATTRACTION","대한불교조계종 동네사",region,"강원 강릉시 사찰로 2","문화,예술 > 종교 > 불교 > 절,사찰",
            128.70,37.60,"https://place.map.kakao.com/9100000000000004",KoreaTime.now().withNano(0));
        when(maps.searchTemples(region)).thenReturn(List.of(temple,localTemple));
        when(maps.searchCathedrals(region)).thenReturn(List.of(listed,parish));
        when(tour.configured()).thenReturn(true);
        when(tour.searchFacilities("성당")).thenReturn(List.of(new TourApiClient.SearchPlace("1","12","임당동 성당","강원 강릉시",128.9005,37.7502)));
        // 절 이름으로 찾은 관광공사 관광지가 1km 안에 있으면 받는다. 종단 이름이 붙은 곳은 마지막 단어로 찾는다.
        when(tour.searchFacilities("테스트사")).thenReturn(List.of(new TourApiClient.SearchPlace("2","12","테스트사(강릉)","강원 강릉시",128.805,37.703)));
        when(tour.searchFacilities("동네사")).thenReturn(List.of());
        discovery.refresh(parent,destination,null,hash); em.flush();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000001")).isPresent();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000002")).isPresent();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000003")).isEmpty();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000004")).isEmpty();
        // 검색 결과에서의 순서(0부터)를 장소에 기록해 후보를 이름순이 아니라 이 순서로 줄 세운다.
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000001").orElseThrow().getDetailsJson()).contains("\"kakaoRank\":0");

        when(tour.searchFacilities("성당")).thenThrow(new RecommendationException("TOUR_UNAVAILABLE","관광정보 오류"));
        jdbc.update("DELETE FROM dbo.places WHERE source_provider='KAKAO' AND source_place_id='9100000000000002'"); em.clear();
        discovery.refresh(parent,destination,null,hash); em.flush();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9100000000000002")).isEmpty();
    }
    // 대표 관광지는 관광공사 연관 관광지(티맵) 인기 순서를 먼저, GPT 제안을 그 뒤에 두고 카카오에서 확인된 곳만 저장해 후보 맨 앞에 둔다.
    // 관광공사에 없는 사찰은 빼고, 이름 목록은 여행지 행에 보관해 같은 여행지에서 다시 계산하지 않는다.
    @Test void popularAndSuggestedLandmarksComeFirstAndAreMadeOncePerDestination() {
        var popular=new KakaoPlace("9200000000000003","ATTRACTION","인기 관광지",region,"강원 강릉시 대표로 3","가정,생활 > 시장",
            128.87,37.76,"https://place.map.kakao.com/9200000000000003",KoreaTime.now().withNano(0));
        var landmark=new KakaoPlace("9200000000000001","ATTRACTION","대표 관광지",region,"강원 강릉시 대표로 1","여행 > 관광,명소",
            128.86,37.76,"https://place.map.kakao.com/9200000000000001",KoreaTime.now().withNano(0));
        var localTemple=new KakaoPlace("9200000000000002","ATTRACTION","동네 절",region,"강원 강릉시 대표로 2","문화,예술 > 종교 > 불교 > 절,사찰",
            128.85,37.75,"https://place.map.kakao.com/9200000000000002",KoreaTime.now().withNano(0));
        when(tour.configured()).thenReturn(true);
        when(tour.regionCodes(region)).thenReturn(Optional.of(new TourApiClient.RegionCodes("51",List.of("150"))));
        when(tour.relatedPlaces(eq("51150"),any(),anyInt())).thenReturn(List.of(
            new TourApiClient.RelatedPlace("경포대","인기 관광지","51150","관광지","쇼핑","시장",1),
            new TourApiClient.RelatedPlace("경포대","강릉역","51150","관광지","기타관광","교통시설",2),
            new TourApiClient.RelatedPlace("경포대","인기 식당","51150","음식","음식","한식",1),
            new TourApiClient.RelatedPlace("경포대","인기 호텔","51150","숙박","숙박","호텔",1)));
        when(maps.findRestaurant("인기 식당",region)).thenReturn(Optional.of(new KakaoPlace("9200000000000004","RESTAURANT","인기 식당",region,
            "강원 강릉시 대표로 4","음식점 > 한식",128.87,37.76,"https://place.map.kakao.com/9200000000000004",KoreaTime.now().withNano(0))));
        when(maps.findStay("인기 호텔",region)).thenReturn(Optional.of(new KakaoPlace("9200000000000005","ACCOMMODATION","인기 호텔",region,
            "강원 강릉시 대표로 5","여행 > 숙박 > 호텔",128.87,37.76,"https://place.map.kakao.com/9200000000000005",KoreaTime.now().withNano(0))));
        when(tour.searchFacilities(anyString())).thenReturn(List.of());
        when(landmarks.configured()).thenReturn(true);
        when(landmarks.names(eq("강릉"),eq(region))).thenReturn(List.of("대표 관광지","동네 절","없는 곳","인기 관광지"));
        when(maps.findLandmark("인기 관광지",region)).thenReturn(Optional.of(popular));
        when(maps.findLandmark("대표 관광지",region)).thenReturn(Optional.of(landmark));
        when(maps.findLandmark("동네 절",region)).thenReturn(Optional.of(localTemple));
        // 조회 장소 8곳·음식점·숙소에 대표 관광지 2곳과 인기 음식점·숙소 1곳씩이 더해진다. 역은 연관 관광지에서, "없는 곳"은 카카오에 없어 버린다.
        assertThat(discovery.refresh(parent,destination,null,hash)).isEqualTo(14);
        em.flush();
        var view=candidates.find(parent,destination,null,hash);
        var attractions=view.attractions();
        assertThat(attractions).extracting(PlaceCandidate::name).startsWith("인기 관광지","대표 관광지").doesNotContain("동네 절");
        // 인기 음식점·숙소는 그 종류의 후보 맨 앞에 오고 '인기' 표시가 붙는다(일정의 식사 고르기에서 쓴다).
        assertThat(view.restaurants().getFirst()).satisfies(p->{ assertThat(p.name()).isEqualTo("인기 식당"); assertThat(p.popular()).isTrue(); });
        assertThat(view.accommodations().getFirst()).satisfies(p->{ assertThat(p.name()).isEqualTo("인기 호텔"); assertThat(p.popular()).isTrue(); });
        assertThat(attractions.getFirst().popular()).isFalse();
        assertThat(places.findById(destination).orElseThrow().getDetailsJson()).contains("landmarks","인기 관광지","\"popular\":true");
        discovery.refresh(parent,destination,null,hash);
        verify(landmarks,times(1)).names(anyString(),anyString());
        verify(tour,times(1)).relatedPlaces(anyString(),any(),anyInt());
    }
    // 같은 종류(해수욕장·해변은 하나)는 후보 앞쪽에 3곳까지만 두고 나머지는 관광지 후보 맨 뒤로 보낸다(빼지는 않는다).
    @Test void sameKindBeyondThreeMovesToTheBackOfCandidates() {
        doReturn(java.util.stream.IntStream.rangeClosed(1,8).mapToObj(i->{
            var d=data(i,"ATTRACTION","강원 강릉시 테스트로 "+i);
            return new KakaoPlace(d.sourceId(),d.type(),d.name(),d.region(),d.address(),
                i<=6?"여행 > 관광,명소 > 해수욕장,해변":"여행 > 관광,명소 > 전망대",d.longitude(),d.latitude(),d.url(),d.checkedAt());
        }).toList()).when(maps).searchPlaces(region,"ATTRACTION");
        discovery.refresh(parent,destination,null,hash); em.flush();
        var names=candidates.find(parent,destination,null,hash).attractions().stream().map(PlaceCandidate::name).toList();
        assertThat(names).containsSubsequence("조회 장소 1","조회 장소 2","조회 장소 3","조회 장소 7","조회 장소 8","조회 장소 4","조회 장소 5","조회 장소 6");
        assertThat(names.subList(names.size()-3,names.size())).containsExactly("조회 장소 4","조회 장소 5","조회 장소 6");
    }
    // '다른 관광지로 다시 만들기'는 같은 조건으로 새 초안을 만들되, 직전 초안의 관광지를 뒤로 보내 남은 후보부터 고른다. 원래 초안은 그대로다.
    @Test void recreatePrefersAttractionsNotInPreviousDraft() throws Exception {
        discovery.refresh(parent,destination,null,hash); em.flush();
        var first=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(14,0),KakaoMapClient.Transport.CAR,60);
        var work=schedules.claim(first); schedules.complete(work,schedules.generate(work)); em.flush();
        var visited=schedules.view(first,null,hash).result().days().stream().flatMap(d->d.items().stream())
            .filter(i->"VISIT".equals(i.type())).map(ItineraryPlan.Item::placeId).collect(java.util.stream.Collectors.toSet());
        var pool=candidates.find(parent,destination,null,hash).attractions().stream().map(PlaceCandidate::placeId).toList();
        assertThat(visited).isNotEmpty();
        mvc.perform(get("/travel/recommendations/itineraries/"+first).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("다른 관광지로 다시 만들기")));
        var redirect=mvc.perform(post("/travel/recommendations/itineraries/"+first+"/recreate").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andReturn().getResponse().getRedirectedUrl();
        var second=UUID.fromString(redirect.substring(redirect.lastIndexOf('/')+1));
        var input=schedules.view(second,null,hash).input();
        assertThat(input.picking().previous()).containsExactlyInAnyOrderElementsOf(visited);
        var fresh=pool.stream().filter(id->!visited.contains(id)).toList();
        var chosen=input.places().attractions().stream().map(PlaceCandidate::placeId).toList();
        assertThat(fresh).isNotEmpty();
        assertThat(chosen.subList(0,Math.min(chosen.size(),fresh.size()))).allMatch(fresh::contains);
        // 남는 시간을 채우는 예비·저녁 관광 후보에도 직전 초안의 관광지는 넣지 않는다(가깝다는 이유로 다시 들어가지 않게).
        assertThat(input.reserve()).extracting(PlaceCandidate::placeId).doesNotContainAnyElementsOf(visited);
        assertThat(input.evening()).extracting(PlaceCandidate::placeId).doesNotContainAnyElementsOf(visited);
        assertThat(schedules.view(first,null,hash).status()).isEqualTo("COMPLETED");
        // 새 초안은 이전 초안을 기억해 두고, 완성되면 이전 초안으로 가는 길과 달라진 부분을 보여 준다.
        assertThat(input.previousJobId()).isEqualTo(first);
        var secondWork=schedules.claim(second); schedules.complete(secondWork,schedules.generate(secondWork)); em.flush();
        mvc.perform(get("/travel/recommendations/itineraries/"+second).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("다른 관광지로 다시 만든 초안이에요")))
            .andExpect(content().string(containsString("/travel/recommendations/itineraries/"+first+"\">이전 초안 보기")))
            .andExpect(content().string(containsString("이전 초안과 달라진 부분")));
    }
    // 여행지 "할 수 있는 것" 목록은 관광공사 대분류마다 한 번씩 받아 소분류로 세고, 여행지 행에 보관해 다시 세지 않는다.
    // 관광공사 오류로 일부만 받았으면 보관하지 않고 빈 목록을 돌려준다.
    @Test void destinationProfileCountsSubcategoriesOnceAndReusesIt() {
        when(tour.configured()).thenReturn(true);
        when(tour.regionCodes(region)).thenReturn(Optional.of(new TourApiClient.RegionCodes("51",List.of("150"))));
        when(tour.placesInClass(eq("51"),eq("150"),anyString(),anyInt())).thenReturn(List.of());
        when(tour.placesInClass(eq("51"),eq("150"),eq("NA"),anyInt())).thenReturn(List.of(
            new TourApiClient.ClassifiedPlace("오대산 소금강계곡","NA010400"),new TourApiClient.ClassifiedPlace("경포해수욕장","NA020900"),
            new TourApiClient.ClassifiedPlace("안목해변","NA020900")));
        var profile=profiles.profile(destination);
        assertThat(DestinationProfile.count(profile,List.of("NA020900"))).isEqualTo(2);
        assertThat(DestinationProfile.names(profile,List.of("NA010400"),3)).containsExactly("오대산 소금강계곡");
        em.flush();
        assertThat(places.findById(destination).orElseThrow().getDetailsJson()).contains("\"profile\"","안목해변");
        assertThat(profiles.profile(destination)).isEqualTo(profile);
        // 대분류 6개(자연·역사·문화·체험·레저·쇼핑)를 한 번씩만 불렀다.
        verify(tour,times(6)).placesInClass(anyString(),any(),anyString(),anyInt());

        jdbc.update("UPDATE dbo.places SET details_json=NULL WHERE place_id=?",destination); em.clear();
        when(tour.placesInClass(eq("51"),eq("150"),eq("HS"),anyInt())).thenThrow(new RecommendationException("TOUR_QUOTA","한도"));
        assertThat(profiles.profile(destination)).isEmpty();
        em.flush();
        assertThat(places.findById(destination).orElseThrow().getDetailsJson()).isNull();
    }
    @Test void sameTypeAndBuildingAddressAreNotDuplicated() {
        var seed=candidates.find(parent,destination,null,hash).attractions().getFirst();
        doReturn(List.of(data(1,"ATTRACTION",seed.address()))).when(maps).searchPlaces(region,"ATTRACTION");
        discovery.refresh(parent,destination,null,hash);
        var pool=candidates.find(parent,destination,null,hash);
        assertThat(pool.attractions()).hasSize(2);
        assertThat(pool.attractions()).anyMatch(p->p.name().equals("조회 장소 1"));
    }
}
