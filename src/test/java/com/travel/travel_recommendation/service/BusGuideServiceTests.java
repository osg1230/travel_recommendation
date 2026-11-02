package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.IntercityBusClient.Service;
import com.travel.travel_recommendation.service.IntercityBusClient.Terminal;
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
        // 도착 터미널: 시외버스 강릉 1개, 고속버스 강릉 1개. 속초도 각각 1개.
        when(buses.terminalIds(Service.INTERCITY,"강릉")).thenReturn(List.of("GN"));
        when(buses.terminalIds(Service.EXPRESS,"강릉")).thenReturn(List.of("EGN"));
        when(buses.terminalIds(Service.INTERCITY,"속초")).thenReturn(List.of("SC"));
        when(buses.terminalIds(Service.EXPRESS,"속초")).thenReturn(List.of("ESC"));
    }

    @Test void typedOriginCombinesIntercityAndExpressAndMergesDuplicateExpressIds() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today.plusDays(10),List.of()));
        when(buses.searchTerminals(eq(Service.INTERCITY),eq("동서울"),anyInt())).thenReturn(List.of(new Terminal("DS","동서울")));
        // 고속버스 동서울은 승강장별 ID가 둘이다. 이름 하나로 묶어 한 번만 보여 준다.
        when(buses.searchTerminals(eq(Service.EXPRESS),eq("동서울"),anyInt())).thenReturn(List.of(new Terminal("E1","동서울"),new Terminal("E2","동서울")));
        when(buses.trips(Service.INTERCITY,"DS","GN",today)).thenReturn(List.of(trip(7,140,22300),trip(21,150,19000)));
        when(buses.trips(Service.EXPRESS,"E1","EGN",today)).thenReturn(List.of());
        when(buses.trips(Service.EXPRESS,"E2","EGN",today)).thenReturn(List.of(trip(6,130,22300),trip(22,135,24000)));
        // "동서울터미널"처럼 적어도 터미널 이름으로 찾는다.
        var guide=service.guide(job,"user",null,"강릉","동서울터미널",null,null);
        assertThat(guide.routes()).extracting(BusGuide.Route::kind).containsExactly("고속버스","시외버스");
        var express=guide.routes().getFirst();
        assertThat(express.fromTerminal()).isEqualTo("동서울"); assertThat(express.trips()).isEqualTo(2);
        assertThat(express.firstDeparture()).isEqualTo("06:00"); assertThat(express.lastDeparture()).isEqualTo("22:00");
        // 조회일 다음 날 00:00으로 읽은 자정 막차는 24:00으로 적는다.
        assertThat(BusGuide.clock(today.plusDays(1).atStartOfDay(),today)).isEqualTo("24:00");
        assertThat(BusGuide.clock(today.atTime(0,50),today)).isEqualTo("00:50");
        assertThat(express.fastestMinutes()).isEqualTo(130);
        assertThat(guide.routes().get(1).minCharge()).isEqualTo(19000);
        // 여행 날짜가 오늘이 아니면 오늘 기준 정보라는 안내를 따로 붙인다(SFR-06-06).
        assertThat(guide.futureTrip()).isTrue();
        verifyNoInteractions(maps);
    }

    @Test void currentLocationBecomesCityKeywordWithoutKeepingCoordinates() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(null,List.of()));
        when(maps.cityKeyword(127.1,37.5)).thenReturn("서울");
        when(buses.searchTerminals(eq(Service.INTERCITY),eq("서울"),anyInt())).thenReturn(List.of(new Terminal("DS","동서울"),new Terminal("SN","서울남부")));
        var guide=service.guide(job,null,new byte[32],"강릉","",127.1,37.5);
        assertThat(guide.from()).isEqualTo("서울");
        assertThat(guide.routes()).isEmpty();
        // 배차가 없으면 버스가 없다고 단정하지 않고 확인을 안내한다.
        assertThat(guide.message()).contains("찾지 못했어요").contains("확인해 주세요");
        assertThat(guide.futureTrip()).isFalse();
        assertThat(guide.toString()).doesNotContain("127.1").doesNotContain("37.5");
    }

    @Test void manyTerminalNamesAreCappedByNameNotById() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of()));
        var many=new ArrayList<Terminal>();
        for(int i=0;i<7;i++) many.add(new Terminal("T"+i,"서울"+i));
        when(buses.searchTerminals(eq(Service.INTERCITY),eq("서울"),anyInt())).thenReturn(many);
        var guide=service.guide(job,"user",null,"강릉","서울",null,null);
        assertThat(guide.message()).contains(BusGuideService.MAX_ORIGINS+"곳만 조회");
        verify(buses,times(BusGuideService.MAX_ORIGINS)).trips(eq(Service.INTERCITY),anyString(),eq("GN"),eq(today));
    }

    @Test void nearbyRegionDefaultsToCurrentDestinationAsOrigin() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of("속초")));
        when(buses.trips(Service.INTERCITY,"GN","SC",today)).thenReturn(List.of(trip(8,70,9900)));
        var guide=service.guide(job,"user",null,"속초","",null,null);
        assertThat(guide.from()).isEqualTo("강릉(지금 여행지)");
        assertThat(guide.routes()).extracting(BusGuide.Route::fromTerminal).containsExactly("강릉");
        verify(buses,never()).searchTerminals(any(),any(),anyInt());
    }

    @Test void rejectsUnknownDestinationMissingOriginAndUnsafeInput() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of("속초")));
        assertThatThrownBy(()->service.guide(job,"user",null,"제주","동서울",null,null)).hasMessageContaining("도착 지역");
        assertThatThrownBy(()->service.guide(job,"user",null,"강릉","",null,null)).hasMessageContaining("출발 도시");
        assertThatThrownBy(()->service.guide(job,"user",null,"강릉","<script>",null,null)).hasMessageContaining("20자 이내");
        verifyNoInteractions(maps);
        verify(buses,never()).trips(any(),any(),any(),any());
    }

    @Test void unapprovedExpressServiceIsSkippedButBothUnapprovedFails() {
        when(itineraries.view(eq(job),any(),any())).thenReturn(view(today,List.of()));
        when(buses.terminalIds(Service.EXPRESS,"강릉")).thenThrow(new RecommendationException("BUS_AUTH","활용신청 확인"));
        when(buses.searchTerminals(eq(Service.INTERCITY),eq("동서울"),anyInt())).thenReturn(List.of(new Terminal("DS","동서울")));
        when(buses.trips(Service.INTERCITY,"DS","GN",today)).thenReturn(List.of(trip(7,140,22300)));
        var guide=service.guide(job,"user",null,"강릉","동서울",null,null);
        assertThat(guide.routes()).extracting(BusGuide.Route::kind).containsExactly("시외버스");
        assertThat(guide.message()).contains("고속버스 정보는 지금 확인할 수 없어 제외했어요");
        when(buses.terminalIds(Service.INTERCITY,"강릉")).thenThrow(new RecommendationException("BUS_AUTH","활용신청 확인"));
        assertThatThrownBy(()->service.guide(job,"user",null,"강릉","동서울",null,null)).hasMessage("활용신청 확인");
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
