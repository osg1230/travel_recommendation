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
    @Test void picksHotelNearestToAttractionsAndDoesNotRepeatRestaurants() {
        // 관광지 1·2 근처의 숙소 5와 멀리 있는 숙소 40. 목록의 첫 후보(40)가 아니라 가까운 5를 골라야 한다.
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1),place(2)),List.of(place(40),place(5)),List.of(place(3),place(6)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var result=generator.generate(input);
        assertThat(result.lodging().chosenPlaceId()).isEqualTo(5L);
        assertThat(result.lodging().options()).extracting(StayOption::placeId).containsExactly(5L,40L);
        assertThat(result.lodging().reason()).contains("관광지 2곳").contains("숙소 후보 2곳 중 가장 가까워요");
        var stay=result.days().getFirst().items().getLast();
        assertThat(stay.placeId()).isEqualTo(5L);
        assertThat(stay.note()).contains("추천 이유:");
        // 이틀 동안 음식점 후보가 2곳이면 같은 곳을 반복하지 않는다.
        var meals=result.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("MEAL")).toList();
        assertThat(meals).extracting(Item::placeId).doesNotHaveDuplicates();
        // 식사 항목에 음식 분류·고른 이유와 공식 자료의 지역 대표 음식을 적는다.
        assertThat(meals.getFirst().note()).contains("음식 분류: 유형").contains("라 골랐어요").contains("초당두부").contains("확인 필요");
        generator.validate(input,result);
        // 날마다 다른 숙소로 바꾼 결과는 받지 않는다.
        var days=new ArrayList<>(result.days());
        var firstItems=new ArrayList<>(days.getFirst().items());
        var last=firstItems.removeLast();
        firstItems.add(new Item(last.type(),40L,last.name(),last.start(),last.end(),last.note(),last.mapUrl(),last.sourceUrl(),null,null));
        days.set(0,new Day(1,days.getFirst().date(),firstItems));
        var changed=new Result(result.destination(),days,result.transport(),result.createdAt()).withLodging(result.lodging());
        assertThatThrownBy(()->generator.validate(input,changed)).isInstanceOf(RecommendationException.class);
    }
    @Test void hotelWithoutLocationIsSkippedWhenAnotherCanBeUsed() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        var noRoad=new PlaceCandidate(41L,"주소 없는 숙소","강원 강릉시 어딘가","호텔","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1),place(2)),List.of(noRoad,place(5)),List.of(place(3)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var result=generator.generate(input);
        assertThat(result.lodging().chosenPlaceId()).isEqualTo(5L);
        assertThat(result.lodging().reason()).contains("1곳이라");
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
    // 잘못된 입력은 generate()가 지도 API를 부르기 전에 실패 사유별로 거른다.
    @Test void generateRejectsPeriodHoursAccommodationAndDateMismatchBeforeCallingMaps() {
        generator.checkInput(input(1,LocalTime.of(18,0)));
        assertRejected(withConditions(conditions(null,LocalDate.of(2026,10,1),false)),"PERIOD_REQUIRED");
        assertRejected(withConditions(conditions(8,LocalDate.of(2026,10,8),true)),"PERIOD_REQUIRED");
        assertRejected(withHours(LocalTime.of(7,0),LocalTime.of(18,0),90),"INVALID_HOURS");
        assertRejected(withHours(LocalTime.of(9,0),LocalTime.of(22,0),90),"INVALID_HOURS");
        assertRejected(withHours(LocalTime.of(9,0),LocalTime.of(12,0),90),"INVALID_HOURS");
        assertRejected(withHours(LocalTime.of(9,0),LocalTime.of(18,0),20),"INVALID_HOURS");
        assertRejected(withHours(LocalTime.of(9,0),LocalTime.of(18,0),200),"INVALID_HOURS");
        // 숙박 필요 여부를 확정하지 않은 2일 이상 일정은 생성하지 않는다.
        assertRejected(withConditions(conditions(2,LocalDate.of(2026,10,2),null)),"ACCOMMODATION_REQUIRED");
        assertRejected(withConditions(conditions(1,LocalDate.of(2026,10,5),false)),"INVALID_PERIOD");
        verifyNoInteractions(maps);
    }
    private ConfirmedTravelConditions conditions(Integer days,LocalDate end,Boolean accommodationNeeded) {
        return new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),end,days,null,2,accommodationNeeded,false,List.of(),"");
    }
    private Input withConditions(ConfirmedTravelConditions conditions) {
        var base=input(1,LocalTime.of(18,0));
        return new Input(base.recommendationId(),base.places(),conditions,base.start(),base.end(),base.transport(),base.visitMinutes());
    }
    private Input withHours(LocalTime start,LocalTime end,int visitMinutes) {
        var base=input(1,LocalTime.of(18,0));
        return new Input(base.recommendationId(),base.places(),base.conditions(),start,end,base.transport(),visitMinutes);
    }
    private void assertRejected(Input input,String code) {
        assertThatThrownBy(()->generator.generate(input)).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo(code));
    }
    @Test void nearbyOrderGroupsLocationsInsteadOfFollowingInputOrder() {
        var base=input(2,LocalTime.of(20,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),2,true,List.of(place(1),place(6),place(2),place(5)),base.places().accommodations(),base.places().restaurants());
        var input=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60);
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
    }
    // 저장 직전 검증은 AI 보조 안내의 일차 수·번호와 문구 형식도 확인한다.
    @Test void finalValidationRejectsAssistThatDoesNotMatchTheServerPlan() {
        var input=input(1,LocalTime.of(18,0)); var result=generator.generate(input);
        var good=new Assist("하루 안내",List.of(new DayAdvice(1,"편한 신발을 준비해 주세요.")),Instant.now());
        generator.validate(input,result.withAssist(good));
        for(var bad:List.of(new Assist("안내",List.of(),Instant.now()),
                new Assist("안내",List.of(new DayAdvice(2,"다른 날 안내")),Instant.now()),
                new Assist("안내",List.of(new DayAdvice(1,"<b>안내</b>")),Instant.now()),
                new Assist(" ",List.of(new DayAdvice(1,"안내")),Instant.now()),
                new Assist("안내",List.of(new DayAdvice(1,"안내")),null)))
            assertThatThrownBy(()->generator.validate(input,result.withAssist(bad))).isInstanceOf(RecommendationException.class);
    }
    // 정기휴무일이 겹치는 관광지는 다른 날로 미루고, 피할 수 없는 충돌은 장소 이름과 함께 알린다.
    @Test void movesPlacesClosedOnADayInsteadOfFailingTheWholeSchedule() {
        var base=input(2,LocalTime.of(20,0)); var pool=base.places();
        var closed=withInfo(pool.attractions().getFirst(),new PlaceOperatingInfo("123","12","09:00~18:00","매주 목요일","","",Instant.now()));
        var input=new Input(base.recommendationId(),new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),
            pool.accommodationNeeded(),List.of(closed,pool.attractions().get(1)),pool.accommodations(),pool.restaurants()),
            base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes());
        // 첫날은 2026-10-01 목요일이므로 휴무인 장소1은 둘째 날로 미뤄야 한다.
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(2L);
        assertThat(result.days().get(1).items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L);
        generator.validate(input,result);
        // 미룰 날이 없으면 어느 장소 때문에 일정을 만들지 못했는지 이름으로 알린다.
        var blocked=new Input(base.recommendationId(),new PlaceCandidateView(pool.jobId(),pool.destination(),1,false,
            List.of(closed),List.of(),pool.restaurants()),conditions(1,LocalDate.of(2026,10,1),false),
            base.start(),base.end(),base.transport(),base.visitMinutes());
        assertThatThrownBy(()->generator.generate(blocked)).isInstanceOfSatisfying(RecommendationException.class,
            e->{ assertThat(e.getCode()).isEqualTo("OPERATING_CONFLICT"); assertThat(e.getMessage()).contains("장소1"); });
    }
    private PlaceCandidate withInfo(PlaceCandidate place,PlaceOperatingInfo info) {
        return new PlaceCandidate(place.placeId(),place.name(),place.address(),place.subtype(),place.description(),
            place.sourceUrl(),place.mapSearchUrl(),place.verifiedAt(),null,null,"SYSTEM",info);
    }
    @Test void checksOperatingRulesDuringGenerationAndFinalValidation() {
        var base=input(1,LocalTime.of(18,0)); var plain=generator.generate(base);
        var original=base.places().attractions().getFirst();
        var info=new PlaceOperatingInfo("123","12","10:00~18:00","연중무휴","","",Instant.now());
        var changed=new PlaceCandidate(original.placeId(),original.name(),original.address(),original.subtype(),original.description(),
            original.sourceUrl(),original.mapSearchUrl(),original.verifiedAt(),null,null,"SYSTEM",info);
        var pool=base.places();
        var withInfo=new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),pool.accommodationNeeded(),List.of(changed,pool.attractions().get(1)),pool.accommodations(),pool.restaurants());
        var input=new Input(base.recommendationId(),withInfo,base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes());
        assertThatThrownBy(()->generator.generate(input)).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("OPERATING_CONFLICT"));
        assertThatThrownBy(()->generator.validate(input,plain)).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("OPERATING_CONFLICT"));
        // 이전 작업 JSON에는 새 필드가 없어도 미확인 후보로 복원된다.
        var mapper=new tools.jackson.databind.json.JsonMapper();
        var tree=mapper.valueToTree(original);
        ((tools.jackson.databind.node.ObjectNode)tree).remove("operatingInfo");
        assertThat(mapper.treeToValue(tree,PlaceCandidate.class).operatingInfo()).isNull();
    }
}
