package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class BusGuideServiceTests {
    private final ItineraryJobService itineraries=mock(ItineraryJobService.class);
    private final IntercityBusClient buses=mock(IntercityBusClient.class);
    private final KakaoMapClient maps=mock(KakaoMapClient.class);
    private final BusGuideService service=new BusGuideService(itineraries,buses,maps);
    private final UUID job=UUID.randomUUID();
    private final LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));

    private View view(LocalDate start,List<String> nearby) {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start,1,null,1,false,true,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(1L,"강릉","강원특별자치도 강릉시","소개","https://visitgangneung.net/",LocalDateTime.of(2026,9,1,0,0)),
            1,false,List.of(),List.of(),List.of());
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),Transport.PUBLIC_TRANSIT,90);
        var regions=nearby.stream().map(n->new NearbyRegion(2L,n,"지역","소개","https://tour.example.kr/",40,"경로",null,List.of())).toList();
        var result=new Result("강릉",List.of(),Transport.PUBLIC_TRANSIT,Instant.now())
            .withNearby(new Nearby("RECOMMENDED","이유","INTERCITY_BUS",regions,null,Instant.now()));
        return new View(job,"COMPLETED","",false,input,result);
    }
    private IntercityBusClient.Trip trip(int hour,int minutes,Integer charge) {
        return new IntercityBusClient.Trip(today.atTime(hour,0),today.atTime(hour,0).plusMinutes(minutes),"일반",charge);
    }
    @BeforeEach void setup() {
        when(buses.terminalId("강릉")).thenReturn("GN");
        when(buses.terminalId("속초")).thenReturn("SC");
    }

    @Test void typedOriginSearchesTerminalsAndSummarizesTodaysTrips() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today.plusDays(10),List.of()));
        when(buses.searchTerminals(eq("동서울"),anyInt())).thenReturn(List.of(new IntercityBusClient.Terminal("DS","동서울")));
        when(buses.trips("DS","GN",today)).thenReturn(List.of(trip(7,140,22300),trip(21,150,19000)));
        // "동서울터미널"처럼 적어도 터미널 이름으로 찾는다.
        var guide=service.guide(job,"user",null,"강릉","동서울터미널",null,null);
        assertThat(guide.routes()).singleElement().satisfies(r->{
            assertThat(r.fromTerminal()).isEqualTo("동서울"); assertThat(r.trips()).isEqualTo(2);
            assertThat(r.firstDeparture()).isEqualTo(LocalTime.of(7,0)); assertThat(r.lastDeparture()).isEqualTo(LocalTime.of(21,0));
            assertThat(r.fastestMinutes()).isEqualTo(140); assertThat(r.minCharge()).isEqualTo(19000);
        });
        // 여행 날짜가 오늘이 아니면 오늘 기준 정보라는 안내를 따로 붙인다(SFR-06-06).
        assertThat(guide.futureTrip()).isTrue();
        verifyNoInteractions(maps);
    }

    @Test void currentLocationBecomesCityKeywordWithoutKeepingCoordinates() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(null,List.of()));
        when(maps.cityKeyword(127.1,37.5)).thenReturn("서울");
        when(buses.searchTerminals(eq("서울"),anyInt())).thenReturn(List.of(
            new IntercityBusClient.Terminal("DS","동서울"),new IntercityBusClient.Terminal("SN","서울남부")));
        when(buses.trips(anyString(),eq("GN"),eq(today))).thenReturn(List.of());
        var guide=service.guide(job,null,new byte[32],"강릉","",127.1,37.5);
        assertThat(guide.from()).isEqualTo("서울");
        assertThat(guide.routes()).isEmpty();
        // 배차가 없으면 버스가 없다고 단정하지 않고 확인을 안내한다.
        assertThat(guide.message()).contains("찾지 못했어요").contains("확인해 주세요");
        assertThat(guide.futureTrip()).isFalse();
        assertThat(guide.toString()).doesNotContain("127.1").doesNotContain("37.5");
    }

    @Test void nearbyRegionDefaultsToCurrentDestinationAsOrigin() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of("속초")));
        when(buses.trips("GN","SC",today)).thenReturn(List.of(trip(8,70,9900)));
        var guide=service.guide(job,"user",null,"속초","",null,null);
        assertThat(guide.from()).isEqualTo("강릉(지금 여행지)");
        assertThat(guide.routes()).extracting(BusGuide.Route::fromTerminal).containsExactly("강릉");
        verify(buses,never()).searchTerminals(any(),anyInt());
    }

    @Test void rejectsUnknownDestinationMissingOriginAndUnsafeInput() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of("속초")));
        assertThatThrownBy(()->service.guide(job,"user",null,"제주","동서울",null,null)).hasMessageContaining("도착 지역");
        assertThatThrownBy(()->service.guide(job,"user",null,"강릉","",null,null)).hasMessageContaining("출발 도시");
        assertThatThrownBy(()->service.guide(job,"user",null,"강릉","<script>",null,null)).hasMessageContaining("20자 이내");
        verifyNoInteractions(maps);
        verify(buses,never()).trips(any(),any(),any());
    }

    @Test void cityKeywordUsesMetroNameOrCityOrCounty() {
        assertThat(KakaoMapClient.cityKeyword("서울특별시","송파구")).isEqualTo("서울");
        assertThat(KakaoMapClient.cityKeyword("부산광역시","금정구")).isEqualTo("부산");
        assertThat(KakaoMapClient.cityKeyword("세종특별자치시","")).isEqualTo("세종");
        assertThat(KakaoMapClient.cityKeyword("강원특별자치도","강릉시")).isEqualTo("강릉");
        assertThat(KakaoMapClient.cityKeyword("강원특별자치도","양양군")).isEqualTo("양양");
        assertThat(KakaoMapClient.cityKeyword("경기도","수원시 영통구")).isEqualTo("수원");
        assertThat(KakaoMapClient.cityKeyword("경기도","")).isNull();
    }
}
