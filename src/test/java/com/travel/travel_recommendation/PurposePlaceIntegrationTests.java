package com.travel.travel_recommendation;

import com.travel.travel_recommendation.service.KoreaTime;
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
    private static final String YEOSU="전남광주통합특별시 여수시", SOURCE="9100000000000001", TIDAL="9100000000000002";
    @Autowired RecommendationJobService recommendations;
    @Autowired PurposePlaceService purposes;
    @Autowired PlaceCandidateService candidates;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean OpenAiPurposeClient ai;
    @MockitoBean KakaoMapClient maps;
    @MockitoBean TourApiClient tour;
    private final byte[] hash=new byte[32];

    @BeforeEach void setup() {
        ExternalPlaceCache.expireExisting(jdbc);
        purposes.clearCaches();
        Arrays.fill(hash,(byte)7);
        when(ai.configured()).thenReturn(true);
        when(ai.terms("상어 관람")).thenReturn(new OpenAiPurposeClient.Terms(List.of("아쿠아리움"),List.of("바다")));
        when(maps.searchNationwide(eq("아쿠아리움"),anyList())).thenReturn(List.of(new KakaoPlace(SOURCE,"ATTRACTION","테스트 아쿠아리움 여수",
            YEOSU,YEOSU+" 오동도로 61-11","여행 > 관광명소 > 아쿠아리움",127.765,34.745,"https://place.map.kakao.com/"+SOURCE,
            KoreaTime.now().withNano(0))));
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
        verify(ai,times(1)).terms(anyString());
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
        when(ai.terms("휴식")).thenReturn(new OpenAiPurposeClient.Terms(List.of(),List.of("휴식")));
        var rest=request("휴식");
        purposes.enrich(rest);
        assertThat(recommendations.conditions(rest,null,hash).purposeSearch().status()).isEqualTo("SKIPPED");
        // 시설로 찾지 않는 목적도 가까운 분류는 남겨 후보를 고를 때 쓴다.
        assertThat(recommendations.conditions(rest,null,hash).purposeThemes()).containsExactly("휴식");
        verify(ai,never()).terms("");
    }

    // 관광공사에서 찾은 여행지 안 시설을 카카오 장소로 맞춰 저장한다. 같은 목적의 다음 요청은 보관한 결과를 쓴다.
    @Test void tourApiFacilityIsMatchedToKakaoAndRepeatedPurposeUsesCache() {
        when(ai.terms("갯벌 체험")).thenReturn(new OpenAiPurposeClient.Terms(List.of("갯벌 체험장"),List.of("바다")));
        when(tour.configured()).thenReturn(true);
        when(tour.searchFacilities("갯벌 체험장")).thenReturn(List.of());
        when(tour.searchFacilities("갯벌체험장")).thenReturn(List.of(
            new TourApiClient.SearchPlace("2001","12","여수 갯벌 노을마을",YEOSU+" 서부로 785-24",127.568,34.790),
            new TourApiClient.SearchPlace("2002","12","보령 갯벌체험장","충청남도 보령시 해안로 607-5",126.52,36.33)));
        when(maps.findNear(eq("여수 갯벌 노을마을"),anyDouble(),anyDouble(),eq(YEOSU))).thenReturn(Optional.of(new KakaoPlace(TIDAL,
            "ATTRACTION","여수갯벌노을마을",YEOSU,YEOSU+" 소라면 사곡리 1223","여행 > 체험여행 > 정보화,체험마을",127.568,34.790,
            "https://place.map.kakao.com/"+TIDAL,KoreaTime.now().withNano(0))));
        when(maps.searchNationwide(eq("갯벌 체험장"),anyList())).thenReturn(List.of());
        var first=request("갯벌 체험");
        purposes.enrich(first);
        var search=recommendations.conditions(first,null,hash).purposeSearch();
        assertThat(search.status()).isEqualTo("FOUND");
        assertThat(search.places()).singleElement().satisfies(p->{
            assertThat(p.destinationName()).isEqualTo("여수"); assertThat(p.name()).isEqualTo("여수갯벌노을마을"); });
        assertThat(places.findBySourceProviderAndSourcePlaceId("KAKAO",TIDAL)).isPresent();
        // 보령은 서비스 여행지가 아니라 카카오로 맞추지 않는다.
        verify(maps,never()).findNear(eq("보령 갯벌체험장"),anyDouble(),anyDouble(),anyString());
        var second=request("갯벌 체험");
        purposes.enrich(second);
        assertThat(recommendations.conditions(second,null,hash).purposeSearch().places()).hasSize(1);
        verify(ai,times(1)).terms("갯벌 체험");
        verify(tour,times(2)).searchFacilities(anyString());
        verify(maps,times(1)).searchNationwide(anyString(),anyList());
    }

    // 관광공사 조회가 안 되면(한도 초과 등) 카카오 검색만으로 계속 찾는다.
    @Test void tourApiFailureFallsBackToKakao() {
        when(tour.configured()).thenReturn(true);
        when(tour.searchFacilities(anyString())).thenThrow(new RecommendationException("TOUR_QUOTA","한도"));
        var id=request("상어 관람");
        purposes.enrich(id);
        var search=recommendations.conditions(id,null,hash).purposeSearch();
        assertThat(search.status()).isEqualTo("FOUND");
        assertThat(search.places()).extracting(p->p.name()).containsExactly("테스트 아쿠아리움 여수");
    }

    // 목적 시설을 하나도 못 찾아도 목적과 가까운 분류(갯벌 체험 → 바다)로 후보를 고른다. 바다가 없는 대전 같은 곳은 빠진다.
    @Test void purposeThemeGuidesCandidatesWhenNoFacilityIsFound() {
        when(ai.terms("갯벌 체험")).thenReturn(new OpenAiPurposeClient.Terms(List.of("갯벌체험"),List.of("바다")));
        when(maps.searchNationwide(eq("갯벌체험"),anyList())).thenReturn(List.of());
        var id=request("갯벌 체험");
        purposes.enrich(id);
        var conditions=recommendations.conditions(id,null,hash);
        assertThat(conditions.purposeSearch().status()).isEqualTo("NONE");
        assertThat(conditions.purposeThemes()).containsExactly("바다");
        recommendations.prepare(id);
        var found=recommendations.view(id,null,hash).candidates();
        assertThat(found).hasSize(5).extracting(DestinationCandidate::name).doesNotContain("대전");
        assertThat(found).allSatisfy(c->assertThat(places.findById(c.placeId()).orElseThrow().getSubtype()).contains("바다"));
    }
}
