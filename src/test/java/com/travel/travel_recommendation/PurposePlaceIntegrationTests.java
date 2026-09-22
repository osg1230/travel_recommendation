package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** 여행 목적 시설 검색(예: 상어 관람 → 아쿠아리움)이 추천 후보·장소 목록에 이어지는지 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class PurposePlaceIntegrationTests {
    private static final String YEOSU="전남광주통합특별시 여수시", SOURCE="9100000000000001";
    @Autowired RecommendationJobService recommendations;
    @Autowired PurposePlaceService purposes;
    @Autowired PlaceCandidateService candidates;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OpenAiPurposeClient ai;
    @MockitoBean KakaoMapClient maps;
    private final byte[] hash=new byte[32];

    @BeforeEach void setup() {
        ExternalPlaceCache.expireExisting(jdbc);
        Arrays.fill(hash,(byte)7);
        when(ai.configured()).thenReturn(true);
        when(ai.keywords("상어 관람")).thenReturn(List.of("아쿠아리움"));
        when(maps.searchNationwide(eq("아쿠아리움"),anyList())).thenReturn(List.of(new KakaoPlace(SOURCE,"ATTRACTION","테스트 아쿠아리움 여수",
            YEOSU,YEOSU+" 오동도로 61-11","여행 > 관광명소 > 아쿠아리움",127.765,34.745,"https://place.map.kakao.com/"+SOURCE,
            LocalDateTime.now(ZoneOffset.UTC).withNano(0))));
    }
    private UUID request(String purpose) {
        var conditions=new ConfirmedTravelConditions("TEXT","상어 보고 싶어","",null,null,null,null,null,null,null,List.of(),"").withPurpose(purpose);
        return recommendations.create(conditions,null,hash);
    }

    @Test void purposeFacilityIsFoundSavedAndPutsItsDestinationFirst() {
        var id=request("상어 관람");
        purposes.enrich(id);
        var search=recommendations.conditions(id,null,hash).purposeSearch();
        assertThat(search.status()).isEqualTo("FOUND");
        assertThat(search.keywords()).containsExactly("아쿠아리움");
        assertThat(search.places()).singleElement().satisfies(p->{
            assertThat(p.destinationName()).isEqualTo("여수"); assertThat(p.name()).isEqualTo("테스트 아쿠아리움 여수"); });
        var saved=places.findBySourceProviderAndSourcePlaceId("KAKAO",SOURCE).orElseThrow();
        assertThat(saved.getPlaceType()).isEqualTo("ATTRACTION"); assertThat(saved.getRegion()).isEqualTo(YEOSU);
        // 지역을 정하지 않은 전국 후보 5곳 중 목적 시설이 있는 여수가 먼저 들어간다.
        recommendations.prepare(id);
        var found=recommendations.view(id,null,hash).candidates();
        assertThat(found).hasSize(5).first().extracting(DestinationCandidate::name).isEqualTo("여수");
        // 이미 찾은 요청은 다시 검색하지 않는다(유료 호출 중복 방지).
        purposes.enrich(id);
        verify(ai,times(1)).keywords(anyString());
        // 여수를 고르면 장소 목록 맨 앞에 목적 시설이 나오고 목적 표시용 ID도 돌려준다.
        var yeosu=found.getFirst();
        jobs.findById(id).orElseThrow().completeRecommendation(mapper.writeValueAsString(List.of(new RankedDestination(1,yeosu,"추천"))));
        var list=candidates.find(id,yeosu.placeId(),null,hash);
        assertThat(list.attractions().getFirst().name()).isEqualTo("테스트 아쿠아리움 여수");
        assertThat(candidates.purpose(id,yeosu.placeId(),null,hash).placeIds()).containsExactly(saved.getId());
    }

    @Test void searchFailureOrNoPurposeDoesNotStopRecommendation() {
        when(maps.searchNationwide(anyString(),anyList())).thenThrow(new RecommendationException("MAP_AUTH","지도 인증 확인"));
        var failed=request("상어 관람");
        purposes.enrich(failed);
        assertThat(recommendations.conditions(failed,null,hash).purposeSearch().status()).isEqualTo("FAILED");
        recommendations.prepare(failed);
        assertThat(recommendations.view(failed,null,hash).candidates()).isNotEmpty();
        // 목적이 없는 요청은 AI를 부르지 않는다.
        var plain=request("");
        purposes.enrich(plain);
        assertThat(recommendations.conditions(plain,null,hash).purposeSearch()).isNull();
        // 시설로 찾을 목적이 아니면 검색하지 않았다고 남긴다.
        when(ai.keywords("휴식")).thenReturn(List.of());
        var rest=request("휴식");
        purposes.enrich(rest);
        assertThat(recommendations.conditions(rest,null,hash).purposeSearch().status()).isEqualTo("SKIPPED");
        verify(ai,never()).keywords("");
    }
}
