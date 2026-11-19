package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.test.context.transaction.BeforeTransaction;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 지역을 정하지 않은 요청에서 AI가 제안한 시·군을 법정동 코드·관광공사 등록 장소로 확인하고, 없던 시·군은 여행지로 추가해
 * 추천 후보 맨 앞에 두는지 실제 SQL Server에서 확인한다. GPT와 관광공사는 모의 객체다.
 * 새 여행지는 추천 작업과 따로 커밋되므로 실제로 생길 수 없는 이름(경상남도 테스트군)을 쓰고 테스트 트랜잭션 밖에서 지운다.
 */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class RegionSuggestionIntegrationTests {
    private static final String NEW_REGION="경상남도 테스트군", GANGNEUNG="강원특별자치도 강릉시";
    @Autowired RecommendationJobService recommendations;
    @Autowired RegionSuggestionService regions;
    @Autowired DestinationCandidateService destinations;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @MockitoBean OpenAiRegionClient ai;
    @MockitoBean TourApiClient tour;
    private final byte[] hash=new byte[32];

    @BeforeTransaction @AfterTransaction void removeTestDestination() {
        jdbc.update("DELETE FROM dbo.places WHERE source_provider='TOURAPI' AND source_place_id=?","REGION_"+NEW_REGION);
    }

    @BeforeEach void setup() {
        Arrays.fill(hash,(byte)9);
        when(ai.configured()).thenReturn(true);
        when(tour.configured()).thenReturn(true);
        when(ai.suggest(any())).thenReturn(new OpenAiRegionClient.Suggestion(List.of("NA010100"),
            List.of(NEW_REGION,GANGNEUNG,"경상북도 울릉군","경상남도 없는군")));
        when(tour.regionCodes(NEW_REGION)).thenReturn(Optional.of(new TourApiClient.RegionCodes("48",List.of("999"))));
        when(tour.regionCodes(GANGNEUNG)).thenReturn(Optional.of(new TourApiClient.RegionCodes("51",List.of("150"))));
        when(tour.placesInClass(eq("48"),eq("999"),eq("NA"),anyInt())).thenReturn(List.of(
            new TourApiClient.ClassifiedPlace("테스트봉","NA010100"),new TourApiClient.ClassifiedPlace("테스트숲","NA010200")));
        when(tour.placesInClass(eq("51"),eq("150"),eq("NA"),anyInt())).thenReturn(List.of(new TourApiClient.ClassifiedPlace("괘방산","NA010100")));
        // 강릉은 보관된 목록 대신 위 모의 응답으로 다시 세게 한다.
        jdbc.update("UPDATE dbo.places SET details_json=NULL WHERE place_type='DESTINATION' AND region=?",GANGNEUNG);
        // 실제 사용 중 울릉군이 여행지로 추가된 DB(2026-11-03)에서도 울릉군을 새 지역처럼 확인하게 이 테스트 안에서만 사용 중지한다(롤백됨).
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_type='DESTINATION' AND region=?","경상북도 울릉군"); em.clear();
    }
    private UUID request(String region,String purpose) {
        var conditions=new ConfirmedTravelConditions("TEXT","지리산 가서 등산하고 싶어요",region,null,null,2,null,2,true,null,List.of(),"",
            true,purpose);
        return recommendations.create(conditions,null,hash);
    }
    private ConfirmedTravelConditions conditions(UUID id) {
        return mapper.readValue(jobs.findById(id).orElseThrow().getRequestJson(),ConfirmedTravelConditions.class);
    }

    @Test void verifiedNewRegionIsAddedAndSuggestedDestinationsComeFirst() {
        var id=request("","지리산 등산");
        regions.enrich(id);
        var suggestion=conditions(id).regionSuggestion();
        var added=places.findBySourceProviderAndSourcePlaceId("TOURAPI","REGION_"+NEW_REGION).orElseThrow();
        var gangneung=destinations.find("강릉").getFirst();
        assertThat(suggestion.status()).isEqualTo("FOUND");
        assertThat(suggestion.categories()).containsExactly("NA010100");
        // 울릉군도 여객선으로 갈 수 있어 다른 지역처럼 확인한다(모의 응답에 법정동 코드가 없어 빠진다). 법정동 코드에 없는 지역도 빠진다.
        assertThat(suggestion.destinationIds()).containsExactly(added.getId(),gangneung.placeId());
        verify(tour).regionCodes("경상북도 울릉군");
        assertThat(added.getPlaceType()).isEqualTo("DESTINATION");
        assertThat(added.getName()).isEqualTo("테스트");
        assertThat(added.getRegion()).isEqualTo(NEW_REGION);
        assertThat(added.getDescription()).isEqualTo("테스트봉 등 산·오름·봉우리를 둘러볼 수 있는 지역입니다.");
        assertThat(added.getSourceUrl()).isEqualTo("https://korean.visitkorea.or.kr/");
        assertThat(added.getDetailsJson()).contains("\"profile\"","테스트봉");
        // 확인한 두 곳이 시·도 분산보다 먼저 후보에 들어간다.
        assertThat(destinations.find(conditions(id))).extracting(DestinationCandidate::name).startsWith("테스트","강릉").hasSize(5);
        // 이미 한 요청은 다시 묻지 않는다.
        regions.enrich(id);
        verify(ai,times(1)).suggest(any());
    }

    // 지역을 정한 요청, AI가 계절에 맞춰 방향을 제안한 요청은 기존 여행지에서 고르므로 제안을 받지 않는다.
    @Test void requestsWithRegionOrWithoutOwnPurposeAreSkipped() {
        regions.enrich(request("강릉","지리산 등산"));
        var suggested=new ConfirmedTravelConditions("TEXT","아무 데나","",null,null,2,null,2,true,null,List.of(),"").withPurpose("가을 숲길 산책",true);
        regions.enrich(recommendations.create(suggested,null,hash));
        verify(ai,never()).suggest(any());
    }

    // 제안한 지역 어디에도 원하는 여행의 등록 장소가 없으면 여행지를 추가하지 않는다.
    @Test void regionsWithoutEvidenceAreNotAdded() {
        when(ai.suggest(any())).thenReturn(new OpenAiRegionClient.Suggestion(List.of("NA010400"),List.of(NEW_REGION)));
        var id=request("","계곡 물놀이");
        regions.enrich(id);
        assertThat(conditions(id).regionSuggestion().status()).isEqualTo("NONE");
        assertThat(places.findBySourceProviderAndSourcePlaceId("TOURAPI","REGION_"+NEW_REGION)).isEmpty();
    }
}
