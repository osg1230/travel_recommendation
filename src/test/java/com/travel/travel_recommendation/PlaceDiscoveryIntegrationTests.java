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
class PlaceDiscoveryIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired PlaceCandidateService candidates;
    @Autowired PlaceDiscoveryService discovery;
    @Autowired ItineraryJobService schedules;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @MockitoBean KakaoMapClient maps;
    private final String region="강원특별자치도 강릉시";
    private MockHttpSession session; private byte[] hash; private UUID parent; private long destination;
    @BeforeEach void setup() throws Exception {
        session=new MockHttpSession(); String token=UUID.randomUUID().toString(); session.setAttribute("recommendationGuestToken",token);
        hash=MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,3,null,2,true,false,List.of(),"");
        parent=recommendations.create(conditions,null,hash); recommendations.prepare(parent);
        var candidate=recommendations.view(parent,null,hash).candidates().getFirst(); destination=candidate.placeId();
        jobs.findById(parent).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"테스트 추천")))); em.flush();
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
        return new KakaoPlace(source,type,"조회 장소 "+id,region,address,"관광",128.87+id*.0001,37.77,"https://place.map.kakao.com/"+source,LocalDateTime.now(ZoneOffset.UTC).withNano(0));
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
        assertThat(schedules.options(parent,destination,null,hash).places().attractions()).hasSize(6);
        var id=schedules.create(parent,destination,null,hash,LocalTime.of(9,0),LocalTime.of(20,0),KakaoMapClient.Transport.CAR,60);
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
        jdbc.update("UPDATE dbo.places SET verified_at=DATEADD(hour,-25,SYSUTCDATETIME()) WHERE source_provider='KAKAO' AND source_place_id=?","9000000000000001");
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE source_provider='KAKAO' AND source_place_id=?","9000000000000002"); em.clear();
        assertThat(candidates.find(parent,destination,null,hash).attractions().stream().filter(p->"KAKAO".equals(p.sourceProvider()))).hasSize(6);
        discovery.refresh(parent,destination,null,hash); em.flush();
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO","9000000000000002").orElseThrow().isActive()).isFalse();
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
