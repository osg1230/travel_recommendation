package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class NearbyRegionServiceTests {
    private final PlaceRepository places=mock(PlaceRepository.class);
    private final KakaoMapClient maps=mock(KakaoMapClient.class);
    private final IntercityBusClient buses=mock(IntercityBusClient.class);
    private final NearbyRegionService service=new NearbyRegionService(places,maps,buses);

    private Place place(long id,String type,String name,String region,String provider) {
        var place=mock(Place.class);
        when(place.getId()).thenReturn(id); when(place.getPlaceType()).thenReturn(type);
        when(place.getName()).thenReturn(name); when(place.getRegion()).thenReturn(region);
        when(place.getSourceProvider()).thenReturn(provider); when(place.getDescription()).thenReturn(name+" 소개");
        when(place.getSourceUrl()).thenReturn("https://tour.example.kr/"+id);
        return place;
    }
    private Input input(Boolean includeNearby,Transport transport) {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",null,null,1,null,2,false,includeNearby,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(1L,"강릉","강원특별자치도 강릉시","소개","https://visitgangneung.net/",LocalDateTime.of(2026,9,1,0,0)),
            1,false,List.of(),List.of(),List.of());
        return new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),transport,90);
    }
    /** lastEnd에 마지막 활동이 끝나는 하루짜리 일정. */
    private Result plan(LocalTime lastEnd) {
        var item=new Item("VISIT",10L,"경포대",LocalTime.of(9,0),lastEnd,"","https://map.kakao.com/","https://tour.example.kr/",null,null);
        return new Result("강릉",List.of(new Day(1,null,List.of(item))),Transport.CAR,Instant.now());
    }
    private void minutes(Map<Double,Integer> byTargetLatitude) {
        when(maps.travelTime(any(),any(),eq(Transport.CAR))).thenAnswer(invocation->{
            KakaoMapClient.Location to=invocation.getArgument(1);
            return new KakaoMapClient.TravelTime(Transport.CAR,byTargetLatitude.get(to.latitude())*60,50000,Instant.now());
        });
    }
    @BeforeEach void setup() {
        var gangneung=place(1,"DESTINATION","강릉","강원특별자치도 강릉시","SYSTEM");
        var sokcho=place(2,"DESTINATION","속초","강원특별자치도 속초시","SYSTEM");
        var yangyang=place(3,"DESTINATION","양양","강원특별자치도 양양군","SYSTEM");
        var ulsan=place(4,"DESTINATION","울산","울산광역시","SYSTEM");
        var unknown=place(5,"DESTINATION","제주","제주특별자치도","SYSTEM");
        when(places.findDestinations()).thenReturn(List.of(gangneung,sokcho,yangyang,ulsan,unknown));
        var beach=place(20,"ATTRACTION","속초해수욕장","강원특별자치도 속초시","SYSTEM");
        var cached=place(21,"ATTRACTION","카카오 후보","강원특별자치도 속초시","KAKAO");
        var hotel=place(22,"ACCOMMODATION","속초 숙소","강원특별자치도 속초시","SYSTEM");
        when(places.findLocalPlaces("강원특별자치도 속초시")).thenReturn(List.of(beach,cached,hotel));
        when(places.findLocalPlaces("강원특별자치도 양양군")).thenReturn(List.of());
        // 터미널 위도로 목적지를 구분한다. 속초 60분, 양양 39분, 울산 235분.
        minutes(Map.of(38.211223,60,38.085294,39,35.536562,235));
    }

    @Test void userChoiceComesFirstAndSpareTimeDecidesWhenUnset() {
        var no=service.recommend(input(false,Transport.CAR),plan(LocalTime.of(10,0)));
        assertThat(no.decision()).isEqualTo("NOT_REQUESTED"); assertThat(no.regions()).isEmpty();
        var busy=service.recommend(input(null,Transport.CAR),plan(LocalTime.of(16,0)));
        assertThat(busy.decision()).isEqualTo("NO_SPARE_TIME");
        verifyNoInteractions(maps,buses);
        // 18:00 종료 기준 15:00에 끝나면 3시간이 남아 주변 지역을 찾는다.
        var spare=service.recommend(input(null,Transport.CAR),plan(LocalTime.of(15,0)));
        assertThat(spare.decision()).isEqualTo("RECOMMENDED");
        assertThat(spare.reason()).contains("3시간");
    }

    @Test void carKeepsRegionsWithinTwoHoursNearestFirstWithOfficialAttractionsOnly() {
        var result=service.recommend(input(true,Transport.CAR),plan(LocalTime.of(17,30)));
        assertThat(result.decision()).isEqualTo("RECOMMENDED");
        assertThat(result.basis()).isEqualTo("CAR");
        assertThat(result.regions()).extracting(NearbyRegion::name).containsExactly("양양","속초");
        assertThat(result.regions()).extracting(NearbyRegion::minutes).containsExactly(39,60);
        var sokcho=result.regions().get(1);
        assertThat(sokcho.attractions()).extracting(NearbyAttraction::name).containsExactly("속초해수욕장");
        assertThat(sokcho.route()).contains("강릉시외버스터미널","속초시외버스터미널");
        assertThat(result.notice()).isNull();
        verifyNoInteractions(buses);
    }

    @Test void busUsesTodaysDirectTripsWithinTwoHours() {
        when(buses.terminalId(anyString())).thenAnswer(invocation->"T-"+invocation.getArgument(0));
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(buses.trips(eq("T-강릉"),eq("T-속초"),eq(today))).thenReturn(List.of(
            new IntercityBusClient.Trip(today.atTime(7,0),today.atTime(8,10),"일반",7400),
            new IntercityBusClient.Trip(today.atTime(19,0),today.atTime(20,5),"우등",null),
            new IntercityBusClient.Trip(today.atTime(12,0),today.atTime(14,30),"일반",6000)));
        when(buses.trips(eq("T-강릉"),eq("T-양양"),eq(today))).thenReturn(List.of());
        when(buses.trips(eq("T-강릉"),eq("T-울산"),eq(today))).thenReturn(List.of());
        var result=service.recommend(input(true,Transport.PUBLIC_TRANSIT),plan(LocalTime.of(17,0)));
        assertThat(result.basis()).isEqualTo("INTERCITY_BUS");
        assertThat(result.regions()).extracting(NearbyRegion::name).containsExactly("속초");
        var bus=result.regions().getFirst().bus();
        // 150분 걸리는 배차는 기준 시간을 넘어 세지 않는다.
        assertThat(bus.trips()).isEqualTo(2);
        assertThat(bus.firstDeparture()).isEqualTo(LocalTime.of(7,0));
        assertThat(bus.lastDeparture()).isEqualTo(LocalTime.of(19,0));
        assertThat(bus.minCharge()).isEqualTo(7400);
        assertThat(result.regions().getFirst().minutes()).isEqualTo(65);
        assertThat(result.notice()).contains("오늘 운행 기준");
        // 배차 정보가 없는 지역은 버스가 없다고 단정하지 않고 판단하지 못한 지역으로 알린다.
        // 울산은 직선거리 150km를 넘어 조회하지 않으므로 목록에도 없다.
        assertThat(result.notice()).contains("판단하지 못한 지역: 양양.");
        verify(buses,never()).trips(any(),eq("T-울산"),any());
        verifyNoInteractions(maps);
    }

    @Test void missingTargetTerminalIsUncheckedButMissingOriginFailsLookup() {
        when(buses.terminalId("강릉")).thenReturn("T-강릉");
        when(buses.terminalId(argThat(name->!"강릉".equals(name))))
            .thenThrow(new RecommendationException("BUS_TERMINAL_NOT_FOUND","터미널 없음"));
        var none=service.recommend(input(true,Transport.PUBLIC_TRANSIT),plan(LocalTime.of(12,0)));
        assertThat(none.decision()).isEqualTo("NONE_FOUND");
        assertThat(none.reason()).contains("확인한 주변 지역이 없어요");
        assertThat(none.notice()).contains("판단하지 못한 지역: 속초, 양양.");
        // 도착 터미널 이름이 겹쳐도 그 지역만 판단하지 못하고 다른 지역은 계속 조회한다.
        reset(buses);
        LocalDate today=LocalDate.now(ZoneId.of("Asia/Seoul"));
        when(buses.terminalId("강릉")).thenReturn("T-강릉");
        when(buses.terminalId("속초")).thenThrow(new RecommendationException("BUS_TERMINAL_AMBIGUOUS","이름 중복"));
        when(buses.terminalId("양양")).thenReturn("T-양양");
        when(buses.trips(eq("T-강릉"),eq("T-양양"),eq(today))).thenReturn(List.of(
            new IntercityBusClient.Trip(today.atTime(9,0),today.atTime(9,40),"일반",4000)));
        var partial=service.recommend(input(true,Transport.PUBLIC_TRANSIT),plan(LocalTime.of(12,0)));
        assertThat(partial.decision()).isEqualTo("RECOMMENDED");
        assertThat(partial.regions()).extracting(NearbyRegion::name).containsExactly("양양");
        assertThat(partial.notice()).contains("판단하지 못한 지역: 속초.");
        reset(buses);
        when(buses.terminalId(anyString())).thenThrow(new RecommendationException("BUS_TERMINAL_NOT_FOUND","출발 터미널 없음"));
        assertThat(service.recommend(input(true,Transport.PUBLIC_TRANSIT),plan(LocalTime.of(12,0))).decision()).isEqualTo("LOOKUP_FAILED");
    }

    @Test void lookupFailureAndNoRegionAreReportedWithoutBreakingPlan() {
        when(maps.travelTime(any(),any(),any())).thenThrow(new RecommendationException("MAP_UNAVAILABLE","지도 정보를 불러오지 못했어요."));
        var failed=service.recommend(input(true,Transport.CAR),plan(LocalTime.of(12,0)));
        assertThat(failed.decision()).isEqualTo("LOOKUP_FAILED");
        assertThat(failed.reason()).isEqualTo("지도 정보를 불러오지 못했어요.");
        reset(maps);
        minutes(Map.of(38.211223,130,38.085294,121,35.536562,235));
        var none=service.recommend(input(true,Transport.CAR),plan(LocalTime.of(12,0)));
        assertThat(none.decision()).isEqualTo("NONE_FOUND");
        assertThat(none.regions()).isEmpty();
    }

    @Test void straightDistanceKeepsNeighborsAndDropsFarRegions() {
        var t=NearbyRegionService.TERMINALS;
        assertThat(NearbyRegionService.straightKilometers(t.get("강릉"),t.get("속초"))).isBetween(45.0,60.0);
        assertThat(NearbyRegionService.straightKilometers(t.get("부산"),t.get("울산"))).isLessThan(NearbyRegionService.MAX_STRAIGHT_KM);
        assertThat(NearbyRegionService.straightKilometers(t.get("부산"),t.get("경주"))).isLessThan(NearbyRegionService.MAX_STRAIGHT_KM);
        assertThat(NearbyRegionService.straightKilometers(t.get("강릉"),t.get("울산"))).isGreaterThan(NearbyRegionService.MAX_STRAIGHT_KM);
        assertThat(NearbyRegionService.straightKilometers(t.get("강릉"),t.get("부산"))).isGreaterThan(NearbyRegionService.MAX_STRAIGHT_KM);
    }

    @Test void spareMinutesUsesLargestGapBeforeDayEnd() {
        assertThat(NearbyRegionService.spareMinutes(input(null,Transport.CAR),plan(LocalTime.of(13,45)))).isEqualTo(255);
        assertThat(NearbyRegionService.spareMinutes(input(null,Transport.CAR),plan(LocalTime.of(18,0)))).isZero();
    }
}
