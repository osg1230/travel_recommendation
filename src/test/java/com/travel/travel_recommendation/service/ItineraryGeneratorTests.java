package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ItineraryGeneratorTests {
    private final KakaoMapClient maps=mock(KakaoMapClient.class);
    private final ItineraryGenerator generator=new ItineraryGenerator(maps);
    private PlaceCandidate place(long id) {
        return new PlaceCandidate(id,"장소"+id,"강원 강릉시 경포로 "+id,"유형","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
    }
    private Input input(int days,LocalTime end) {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,days),days,null,2,days>1,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),days,days>1,
            List.of(place(1),place(2)),days>1?List.of(place(4)):List.of(),List.of(place(3)));
        return new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),end,KakaoMapClient.Transport.CAR,90);
    }
    @BeforeEach void setup() {
        when(maps.locate(anyString())).thenAnswer(invocation->{
            String address=invocation.getArgument(0); int id=Integer.parseInt(address.substring(address.lastIndexOf(' ')+1));
            return new KakaoMapClient.Location(address,128.8+id*0.001,37.7,Instant.now());
        });
        when(maps.travelTime(any(),any(),any())).thenAnswer(invocation->new KakaoMapClient.TravelTime(invocation.getArgument(2),601,3000,Instant.now()));
    }
    @Test void dayTripIncludesEveryAttractionMealAndTravelAllowance() {
        var input=input(1,LocalTime.of(18,0)); var result=generator.generate(input);
        var items=result.days().getFirst().items();
        assertThat(items.stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
        assertThat(items.stream().filter(i->i.type().equals("TRANSIT"))).allSatisfy(i->assertThat(Duration.between(i.start(),i.end()).toMinutes()).isEqualTo(21));
        assertThat(items.stream().filter(i->i.type().equals("MEAL"))).hasSize(1);
        assertThat(items.stream().filter(i->i.type().equals("STAY"))).isEmpty();
        assertThat(result.days().getFirst().date()).isEqualTo(LocalDate.of(2026,10,1));
        generator.validate(input,result);
    }
    @Test void overnightEndsAtHotelAndNextDayStartsWithTransferFromHotel() {
        var input=input(2,LocalTime.of(18,0)); var result=generator.generate(input);
        assertThat(result.days()).hasSize(2);
        assertThat(result.days().getFirst().items().getLast().type()).isEqualTo("STAY");
        assertThat(result.days().get(1).items().getFirst().type()).isEqualTo("TRANSIT");
        assertThat(result.days().get(1).items().getFirst().name()).startsWith("장소4 →");
        assertThat(result.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
    }
    @Test void rejectsNotEnoughPlacesWithoutExternalCalls() {
        assertThatThrownBy(()->generator.generate(input(3,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("INSUFFICIENT_PLACES"));
        verifyNoInteractions(maps);
    }
    @Test void overflowLongRoutesAndMapFailuresDoNotProduceResult() {
        assertThatThrownBy(()->generator.generate(input(1,LocalTime.of(13,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("TIME_OVERLAP"));
        when(maps.travelTime(any(),any(),any())).thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR,7201,90000,Instant.now()));
        assertThatThrownBy(()->generator.generate(input(1,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("TRAVEL_TIME_EXCEEDED"));
        doThrow(new RecommendationException("MAP_AUTH","설정 확인")).when(maps).locate(anyString());
        assertThatThrownBy(()->generator.generate(input(1,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("MAP_AUTH"));
    }
    @Test void rejectsMissingTransferAndDuplicatedVisitDuringFinalValidation() {
        var input=input(1,LocalTime.of(18,0)); var good=generator.generate(input); var day=good.days().getFirst();
        var items=new ArrayList<>(day.items()); items.removeIf(i->i.type().equals("TRANSIT"));
        var bad=new Result(good.destination(),List.of(new Day(1,day.date(),items)),good.transport(),good.createdAt());
        assertThatThrownBy(()->generator.validate(input,bad)).isInstanceOf(RecommendationException.class);
        items=new ArrayList<>(day.items()); Item second=items.get(2);
        items.set(2,new Item(second.type(),1L,second.name(),second.start(),second.end(),second.note(),second.mapUrl(),second.sourceUrl(),second.travelSeconds(),second.checkedAt()));
        var duplicate=new Result(good.destination(),List.of(new Day(1,day.date(),items)),good.transport(),good.createdAt());
        assertThatThrownBy(()->generator.validate(input,duplicate)).isInstanceOf(RecommendationException.class);
    }
    @Test void nearbyOrderGroupsLocationsInsteadOfFollowingInputOrder() {
        var base=input(2,LocalTime.of(20,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),2,true,List.of(place(1),place(6),place(2),place(5)),base.places().accommodations(),base.places().restaurants());
        var input=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60);
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
    }
}
