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
        // 숙박하는 날은 숙소 도착 뒤 숙소 근처 저녁으로 하루를 마친다.
        var first=result.days().getFirst().items();
        assertThat(first.get(ItineraryGenerator.stayIndex(first)).type()).isEqualTo("STAY");
        assertThat(first.getLast().type()).isEqualTo("MEAL");
        assertThat(result.days().get(1).items().getFirst().type()).isEqualTo("TRANSIT");
        assertThat(result.days().get(1).items().getFirst().name()).startsWith("장소4 →");
        assertThat(result.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
    }
    @Test void picksHotelNearestToAttractionsAndDoesNotRepeatRestaurants() {
        // 관광지 1·2 근처의 숙소 5와 멀리 있는 숙소 40. 목록의 첫 후보(40)가 아니라 가까운 5를 골라야 한다.
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1),place(2)),List.of(place(40),place(5)),List.of(place(3),place(6),place(7)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var result=generator.generate(input);
        assertThat(result.lodging().chosenPlaceId()).isEqualTo(5L);
        assertThat(result.lodging().options()).extracting(StayOption::placeId).containsExactly(5L,40L);
        assertThat(result.lodging().reason()).contains("관광지 2곳").contains("숙소 후보 2곳 중 가장 가까워요");
        var firstDay=result.days().getFirst().items();
        var stay=firstDay.get(ItineraryGenerator.stayIndex(firstDay));
        assertThat(stay.placeId()).isEqualTo(5L);
        // 숙소 후보와 확인 안내는 초안의 첫 숙박 항목에 한 번만 보여 주므로 항목마다 적지 않는다.
        assertThat(stay.note()).isEqualTo("숙소 도착·휴식");
        // 이틀 동안 식사 3번(1일차 점심·저녁, 2일차 점심)에 음식점 후보가 3곳이면 같은 곳을 반복하지 않는다.
        var meals=result.days().stream().flatMap(d->d.items().stream()).filter(i->i.type().equals("MEAL")).toList();
        assertThat(meals).extracting(Item::placeId).doesNotHaveDuplicates();
        // 식사 항목에 음식 분류·고른 이유를 적고, 지역 대표 음식은 첫 식사에만 적는다. 운영정보가 없으면 '확인 필요' 줄을 붙이지 않는다.
        assertThat(meals.getFirst().note()).startsWith("식사 · 유형").contains("위치: 장소").contains("초당두부").doesNotContain("확인 필요");
        assertThat(meals.subList(1,meals.size())).allSatisfy(m->assertThat(m.note()).doesNotContain("초당두부"));
        generator.validate(input,result);
        // 날마다 다른 숙소로 바꾼 결과는 받지 않는다.
        var days=new ArrayList<>(result.days());
        var firstItems=new ArrayList<>(days.getFirst().items());
        int stayAt=ItineraryGenerator.stayIndex(firstItems);
        var last=firstItems.get(stayAt);
        firstItems.set(stayAt,new Item(last.type(),40L,last.name(),last.start(),last.end(),last.note(),last.mapUrl(),last.sourceUrl(),null,null));
        days.set(0,new Day(1,days.getFirst().date(),firstItems));
        var changed=new Result(result.destination(),days,result.transport(),result.createdAt()).withLodging(result.lodging());
        assertThatThrownBy(()->generator.validate(input,changed)).isInstanceOf(RecommendationException.class);
    }
    // 09~18시 1박 2일: 1일차는 관광 → 숙소 도착·휴식 → 18시 숙소 근처 저녁(점심과 다른 곳), 2일차(마지막 날)는 저녁이 없다.
    @Test void stayDayAddsDinnerNearHotelAfterArrival() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        // 숙소 5 바로 옆 음식점 7, 관광지 옆 음식점 3.
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1),place(2)),List.of(place(5)),List.of(place(3),place(7)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var result=generator.generate(input);
        var first=result.days().getFirst().items();
        int stayAt=ItineraryGenerator.stayIndex(first);
        var meals=first.stream().filter(i->i.type().equals("MEAL")).toList();
        assertThat(meals).hasSize(2);
        var dinner=meals.getLast();
        assertThat(first.getLast()).isEqualTo(dinner);
        assertThat(first.indexOf(dinner)).isGreaterThan(stayAt);
        assertThat(dinner.start()).isEqualTo(LocalTime.of(18,0));
        assertThat(dinner.note()).startsWith("저녁 식사").contains("위치: 장소5에서 직선 약");
        assertThat(dinner.placeId()).isNotEqualTo(meals.getFirst().placeId());
        // 숙소 휴식은 저녁 출발 전까지 이어진다. 관광은 하루 종료 시간(18시) 안이다.
        assertThat(first.get(stayAt).end()).isBefore(LocalTime.of(18,0));
        assertThat(first.stream().filter(i->i.type().equals("VISIT"))).allSatisfy(i->assertThat(i.end()).isBeforeOrEqualTo(LocalTime.of(18,0)));
        assertThat(result.days().get(1).items().stream().filter(i->i.type().equals("MEAL"))).hasSize(1);
        generator.validate(input,result);
        var report=ItineraryConditionReport.evaluate(input,result);
        assertThat(report.complete()).isTrue();
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("시간 충돌"); assertThat(c.detail()).contains("저녁 관광은 22:00까지"); });
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("숙박"); assertThat(c.detail()).contains("숙소 근처에서 저녁"); });
        // 저녁 뒤에 관광을 더 넣은 결과는 받지 않는다.
        var days=new ArrayList<>(result.days());
        var items=new ArrayList<>(first);
        var extra=items.get(stayAt);
        items.add(new Item("VISIT",2L,"장소2",dinner.end(),dinner.end().plusMinutes(90),"",extra.mapUrl(),extra.sourceUrl(),null,null));
        days.set(0,new Day(1,days.getFirst().date(),items));
        var changed=new Result(result.destination(),days,result.transport(),result.createdAt()).withLodging(result.lodging());
        assertThatThrownBy(()->generator.validate(input,changed)).isInstanceOf(RecommendationException.class);
    }
    // 1박 2일: 숙소 근처 저녁 뒤 가까운 저녁 관광 한 곳(60분)을 넣고 숙소로 돌아온다. 마지막 날에는 저녁 관광이 없다.
    @Test void stayDayAddsEveningSpotAfterDinnerAndReturnsToHotel() {
        var base=input(2,LocalTime.of(18,0));
        var tower=new PlaceCandidate(8L,"전망대8","강원 강릉시 경포로 8","여행 > 관광,명소 > 전망대","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var input=base.withEvening(List.of(tower));
        var result=generator.generate(input);
        var first=result.days().getFirst().items();
        int stayAt=ItineraryGenerator.stayIndex(first);
        var after=first.subList(stayAt+1,first.size()).stream().map(Item::type).filter(t->!t.equals("TRANSIT")).toList();
        assertThat(after).containsExactly("MEAL","VISIT","STAY");
        var evening=first.stream().filter(i->i.type().equals("VISIT") && i.placeId()==8L).findFirst().orElseThrow();
        assertThat(Duration.between(evening.start(),evening.end()).toMinutes()).isEqualTo(ItineraryGenerator.EVENING_MINUTES);
        assertThat(evening.start()).isAfter(LocalTime.of(19,0));
        assertThat(evening.note()).startsWith("저녁 관광").contains("밤에도 둘러볼 수 있는지");
        assertThat(first.getLast().type()).isEqualTo("STAY");
        assertThat(first.getLast().placeId()).isEqualTo(first.get(stayAt).placeId());
        assertThat(first.getLast().end()).isBeforeOrEqualTo(LocalTime.of(22,0));
        assertThat(result.days().get(1).items()).noneMatch(i->Long.valueOf(8L).equals(i.placeId()));
        generator.validate(input,result);
        var report=ItineraryConditionReport.evaluate(input,result);
        assertThat(report.complete()).isTrue();
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("숙박"); assertThat(c.detail()).contains("저녁 관광"); });
        // 저녁 관광 후보가 아닌 곳을 저녁에 넣은 결과는 받지 않는다.
        var items=new ArrayList<>(first);
        int at=items.indexOf(evening);
        items.set(at,new Item("VISIT",2L,"장소2",evening.start(),evening.end(),"",evening.mapUrl(),evening.sourceUrl(),null,null));
        var days=new ArrayList<>(result.days()); days.set(0,new Day(1,days.getFirst().date(),items));
        var forged=new Result(result.destination(),days,result.transport(),result.createdAt()).withLodging(result.lodging());
        assertThatThrownBy(()->generator.validate(input,forged)).isInstanceOf(RecommendationException.class);
    }
    // 하루 시간이 길고 체류가 짧아도 예비 관광지로 채우는 것은 하루 4곳까지다.
    @Test void spareFillStopsAtFourVisitsADay() {
        var base=input(1,LocalTime.of(21,0));
        var reserve=java.util.stream.LongStream.rangeClosed(10,17).mapToObj(this::place).toList();
        var input=new Input(base.recommendationId(),base.places(),base.conditions(),LocalTime.of(8,0),LocalTime.of(21,0),base.transport(),30,null,null,reserve);
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT"))).hasSize(TripConstraints.MAX_AUTO_DAILY);
        generator.validate(input,result);
    }
    // 첫날은 집에서 출발(07:00, 편도 120분 + 여유 10분 → 09:10 도착)해 도착 뒤 관광하고, 마지막 날은 20:00까지 집에 도착하도록 마친다.
    @Test void homeDepartureAndReturnFrameTheTrip() {
        var home=new HomeTrip("서울 송파구",LocalTime.of(7,0),LocalTime.of(20,0),120,"자동차 · 카카오 길찾기",Instant.now());
        var input=input(2,LocalTime.of(18,0)).withHome(home);
        assertThat(input.dayStart(1)).isEqualTo(LocalTime.of(9,10));
        assertThat(input.dayEnd(2)).isEqualTo(LocalTime.of(17,50));
        assertThat(input.dayStart(2)).isEqualTo(LocalTime.of(9,0));
        var result=generator.generate(input);
        var first=result.days().getFirst().items();
        assertThat(first.getFirst().placeId()).isNull();
        assertThat(first.getFirst().type()).isEqualTo("TRANSIT");
        assertThat(first.getFirst().start()).isEqualTo(LocalTime.of(7,0));
        assertThat(first.getFirst().end()).isEqualTo(LocalTime.of(9,10));
        assertThat(first.getFirst().name()).isEqualTo("서울 송파구 → 강릉");
        assertThat(first.get(1).start()).isEqualTo(LocalTime.of(9,10));
        var last=result.days().get(1).items();
        assertThat(last.getLast().placeId()).isNull();
        assertThat(last.getLast().name()).endsWith("→ 서울 송파구");
        assertThat(Duration.between(last.getLast().start(),last.getLast().end()).toMinutes()).isEqualTo(130);
        assertThat(last.getLast().end()).isBeforeOrEqualTo(LocalTime.of(20,0));
        generator.validate(input,result);
        var report=ItineraryConditionReport.evaluate(input,result);
        assertThat(report.complete()).isTrue();
        // 집 이동은 같은 주소 안 이동으로 세지 않고, 편도 시간과 근거를 따로 적는다.
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("이동시간");
            assertThat(c.detail()).contains("모두 카카오 예상 이동시간").contains("집 → 여행지는 편도 약 120분"); });
    }
    // 돌아갈 곳이 출발지와 다르면 마지막 날 그곳까지의 시간(200분)으로 관광을 마치는 시각을 정하고, 마지막 이동도 그곳으로 간다.
    @Test void returnToAnotherPlaceUsesItsOwnTravelTime() {
        var home=new HomeTrip("서울 송파구",LocalTime.of(7,0),LocalTime.of(20,0),120,"직접 입력한 편도 시간",null,"대전 서구",200,"직접 입력한 편도 시간");
        var input=input(2,LocalTime.of(18,0)).withHome(home);
        assertThat(input.dayEnd(2)).isEqualTo(LocalTime.of(16,30));
        var result=generator.generate(input);
        var last=result.days().get(1).items().getLast();
        assertThat(last.name()).endsWith("→ 대전 서구");
        assertThat(Duration.between(last.start(),last.end()).toMinutes()).isEqualTo(210);
        assertThat(last.note()).contains("20:00까지 대전 서구에 도착");
        generator.validate(input,result);
        assertThat(ItineraryConditionReport.evaluate(input,result).checks()).anySatisfy(c->assertThat(c.detail()).contains("여행지 → 대전 서구는 약 200분"));
    }
    // 당일치기에서 집 오가는 시간을 빼면 4시간이 안 되면 만들지 않고 이유를 알린다.
    @Test void dayTripTooShortAfterHomeTravelIsRejected() {
        var home=new HomeTrip("서울",LocalTime.of(9,0),LocalTime.of(18,0),180,"직접 입력",null);
        var input=input(1,LocalTime.of(18,0)).withHome(home);
        assertThatThrownBy(()->generator.checkInput(input)).isInstanceOfSatisfying(RecommendationException.class,
            e->assertThat(e.getMessage()).contains("4시간보다 짧아요").contains("12:10~14:50"));
    }
    // 하루 종료를 늦게(20:50) 정해도 숙박하는 날 저녁은 한 번만 넣는다(점심 1 + 저녁 1). 마지막 날은 점심만 있다.
    @Test void stayDayDinnerIsAddedOnlyOnce() {
        var late=input(2,LocalTime.of(20,50));
        var result=generator.generate(late);
        var first=result.days().getFirst().items();
        assertThat(first.stream().filter(i->i.type().equals("MEAL"))).hasSize(2);
        assertThat(first.stream().filter(i->i.type().equals("MEAL") && i.note().startsWith("저녁 식사"))).hasSize(1);
        assertThat(result.days().get(1).items().stream().filter(i->i.type().equals("MEAL"))).hasSize(1);
        generator.validate(late,result);
    }
    // 중심 장소가 둘이면 날마다 하나씩 그날 첫 관광지로 넣고, 가까운 관광지는 그 중심 장소의 날에 넣는다.
    @Test void eachDayStartsAtItsCenterPlaceWithNearbyAttractions() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        // 1·2·3은 서로 가깝고, 29·30·31은 그쪽과 멀리 떨어진 무리다(경도 0.001 = 약 90m).
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(2),place(29),place(3),place(31),place(1),place(30)),List.of(place(16)),List.of(place(4),place(28)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,60,
            null,new Constraints(List.of(),null,false,null,List.of(),null,List.of(30L,1L)),List.of());
        var result=generator.generate(input);
        var day1=result.days().get(0).items().stream().filter(i->i.type().equals("VISIT")).toList();
        var day2=result.days().get(1).items().stream().filter(i->i.type().equals("VISIT")).toList();
        assertThat(day1.getFirst().placeId()).isEqualTo(30L);
        assertThat(day1.getFirst().note()).contains("이 날 일정의 중심으로 고른 곳이라 첫 방문지로 넣었어요");
        assertThat(day1).extracting(Item::placeId).containsExactlyInAnyOrder(30L,29L,31L);
        assertThat(day2.getFirst().placeId()).isEqualTo(1L);
        assertThat(day2).extracting(Item::placeId).containsExactlyInAnyOrder(1L,2L,3L);
        assertThat(day1.get(1).note()).doesNotContain("중심으로 고른");
        generator.validate(input,result);
    }
    // 중심 장소 둘이 서로 가까우면(1·2는 약 90m) 날을 나누지 않고 1일차에 함께 넣는다. 다음 날 같은 곳으로 되돌아가지 않게 하기 위해서다.
    @Test void nearbyCenterPlacesShareTheFirstDay() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(2),place(29),place(3),place(31),place(1),place(30)),List.of(place(16)),List.of(place(4),place(28)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,60,
            null,new Constraints(List.of(),null,false,null,List.of(),null,List.of(1L,2L)),List.of());
        var result=generator.generate(input);
        var day1=result.days().get(0).items().stream().filter(i->i.type().equals("VISIT")).toList();
        assertThat(day1.getFirst().placeId()).isEqualTo(1L);
        assertThat(day1).extracting(Item::placeId).contains(1L,2L);
        assertThat(day1.stream().filter(i->i.placeId()==2L).findFirst().orElseThrow().note()).contains("가까운 중심 장소와 같은 날로 묶었어요");
        generator.validate(input,result);
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
    @Test void chosenStayTierComesFirstAndFallsBackWhenMissing() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        // 가까운 호텔(5)과 먼 캠핑장(40). 절약형을 고르면 멀어도 캠핑장을 먼저 쓴다.
        var hotel=new PlaceCandidate(5L,"가까운 호텔","강원 강릉시 경포로 5","호텔","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
        var camp=new PlaceCandidate(40L,"먼 캠핑장","강원 강릉시 경포로 40","여행 > 숙박 > 야영,캠핑장","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1),place(2)),List.of(hotel,camp),List.of(place(3)));
        var budget=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90,null,
            new Constraints(List.of(),null,false,null,List.of(),"BUDGET"));
        var result=generator.generate(budget);
        assertThat(result.lodging().chosenPlaceId()).isEqualTo(40L);
        assertThat(result.lodging().options()).extracting(StayOption::placeId).containsExactly(40L,5L);
        assertThat(result.lodging().reason()).contains("절약형");
        generator.validate(budget,result);
        // 여유형 숙소가 없으면 전체 후보에서 가장 가까운 곳을 쓰고 그 사실을 알린다.
        var comfort=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90,null,
            new Constraints(List.of(),null,false,null,List.of(),"COMFORT"));
        var fallback=generator.generate(comfort);
        assertThat(fallback.lodging().chosenPlaceId()).isEqualTo(5L);
        assertThat(fallback.lodging().reason()).contains("여유형").contains("후보에 없어");
    }
    @Test void unknownStayTierIsRejected() {
        var pool=List.of(place(1),place(2));
        assertThatThrownBy(()->TripConstraints.select(pool,1,LocalTime.of(9,0),LocalTime.of(18,0),90,
            new Constraints(List.of(),null,false,null,List.of(),"LUXURY"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsNotEnoughPlacesWithoutExternalCalls() {
        assertThatThrownBy(()->generator.generate(input(3,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("INSUFFICIENT_PLACES"));
        verifyNoInteractions(maps);
    }
    @Test void overflowLongRoutesAndMapFailuresDoNotProduceResult() {
        // 첫 관광지와 점심조차 하루 시간에 들어가지 않으면 일정을 만들지 않는다.
        assertThatThrownBy(()->generator.generate(withHours(LocalTime.of(9,0),LocalTime.of(13,0),180))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("TIME_OVERLAP"));
        when(maps.travelTime(any(),any(),any())).thenReturn(new KakaoMapClient.TravelTime(KakaoMapClient.Transport.CAR,7201,90000,Instant.now()));
        assertThatThrownBy(()->generator.generate(input(1,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("TRAVEL_TIME_EXCEEDED"));
        doThrow(new RecommendationException("MAP_AUTH","설정 확인")).when(maps).locate(anyString());
        assertThatThrownBy(()->generator.generate(input(1,LocalTime.of(18,0)))).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("MAP_AUTH"));
    }
    // 09:00~13:00이면 관광지 1곳과 점심만 들어간다. 남은 관광지는 실패 대신 빼고 결과에 적는다.
    @Test void dropsAttractionsThatDoNotFitAndRecordsThem() {
        var input=input(1,LocalTime.of(13,0)); var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("MEAL"))).hasSize(1);
        assertThat(result.skipped()).extracting(PlaceRef::placeId).containsExactly(2L);
        generator.validate(input,result);
        var report=ItineraryConditionReport.evaluate(input,result);
        assertThat(report.complete()).isTrue();
        assertThat(report.checks()).anySatisfy(c->assertThat(c.detail()).contains("장소2").contains("넣지 않았어요"));
        // 빠진 관광지를 skipped에 적지 않은 결과는 받지 않는다.
        var hidden=new Result(result.destination(),result.days(),result.transport(),result.createdAt());
        assertThatThrownBy(()->generator.validate(input,hidden)).isInstanceOf(RecommendationException.class);
    }
    // 섬처럼 한 곳만 2시간 넘게 걸리면 일정 전체를 실패시키지 않고 그곳만 뺀다. 필수 장소면 이름과 함께 알린다.
    @Test void unreachableAttractionIsLeftOutInsteadOfFailingTheSchedule() {
        // 장소 90은 멀리 있는 섬으로 본다(좌표 128.8+90×0.001). 그곳을 잇는 구간만 2시간을 넘는다.
        when(maps.travelTime(any(),any(),any())).thenAnswer(invocation->{
            KakaoMapClient.Location a=invocation.getArgument(0), b=invocation.getArgument(1);
            boolean island=Math.max(a.longitude(),b.longitude())>128.885;
            return new KakaoMapClient.TravelTime(invocation.getArgument(2),island?7300:601,3000,Instant.now());
        });
        var base=input(1,LocalTime.of(18,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),1,false,
            List.of(place(1),place(90),place(2)),List.of(),base.places().restaurants());
        var input=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60);
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L);
        assertThat(result.skipped()).extracting(PlaceRef::placeId).containsExactly(90L);
        generator.validate(input,result);
        var required=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60,null,
            new Constraints(List.of(90L),null,false,null,List.of()));
        assertThatThrownBy(()->generator.generate(required)).isInstanceOfSatisfying(RecommendationException.class,e->{
            assertThat(e.getCode()).isEqualTo("TRAVEL_TIME_EXCEEDED"); assertThat(e.getMessage()).contains("장소90"); });
    }
    private PlaceCandidate restaurant(long id,String subtype) {
        return new PlaceCandidate(id,"식당"+id,"강원 강릉시 경포로 "+id,subtype,"소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
    }
    private Input dayTrip(List<PlaceCandidate> attractions,List<PlaceCandidate> restaurants,int visitMinutes,List<PlaceCandidate> reserve) {
        var base=input(1,LocalTime.of(18,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),1,false,attractions,List.of(),restaurants);
        return new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),visitMinutes,null,null,reserve);
    }
    // 가장 가까운 곳이 아이스크림 가게·술집이어도 끼니로는 일반 음식점을 고른다.
    @Test void mealsSkipSnackAndBarPlaces() {
        var input=dayTrip(List.of(place(1),place(2)),List.of(restaurant(3,"음식점 > 간식 > 아이스크림"),
            restaurant(4,"음식점 > 술집 > 호프,요리주점"),restaurant(30,"음식점 > 한식")),90,List.of());
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("MEAL")).map(Item::placeId)).containsExactly(30L);
        generator.validate(input,result);
    }
    // 11시가 지나 다음 관광지를 마치면 13시 30분을 넘길 때는 점심을 먼저 먹는다(11:30에 첫 관광을 마치면 다음 관광은 14:21에 끝난다).
    @Test void lunchComesBeforeAVisitThatWouldEndTooLate() {
        var input=dayTrip(List.of(place(1),place(2),place(5)),List.of(place(3)),150,List.of());
        var items=generator.generate(input).days().getFirst().items();
        var meal=items.stream().filter(i->i.type().equals("MEAL")).findFirst().orElseThrow();
        assertThat(meal.start()).isEqualTo(LocalTime.NOON);
        assertThat(items.stream().filter(i->i.type().equals("VISIT")).filter(i->i.end().isBefore(meal.start()))).hasSize(1);
    }
    // 고른 관광지를 다 넣고도 시간이 남으면 예비 관광지로 채우되, 수정안의 교체 후보로 2곳은 남긴다(체류 30분이라 시간은 충분하다).
    @Test void spareTimeIsFilledFromReserveKeepingTwoForRevisions() {
        var input=dayTrip(List.of(place(1),place(2)),List.of(place(3)),30,List.of(place(5),place(6),place(7),place(8)));
        var result=generator.generate(input);
        var visits=result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).toList();
        assertThat(visits).extracting(Item::placeId).startsWith(1L,2L).hasSize(4);
        assertThat(visits.subList(2,4)).allSatisfy(i->assertThat(i.note()).contains("하루 시간이 남아 더 넣은 곳이에요."));
        generator.validate(input,result);
        // 체류 90분이면 14:03~15:33, 15:54~17:24 두 곳이 더 들어가고 하루 종료(18시)는 넘지 않는다.
        var longer=dayTrip(List.of(place(1),place(2)),List.of(place(3)),90,List.of(place(5),place(6),place(7),place(8)));
        var longVisits=generator.generate(longer).days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).toList();
        assertThat(longVisits).hasSize(4);
        assertThat(longVisits.getLast().end()).isEqualTo(LocalTime.of(17,24));
        // 하루 방문 수를 정했으면 채우지 않는다.
        var fixed=new Input(input.recommendationId(),input.places(),input.conditions(),input.start(),input.end(),input.transport(),90,null,
            new Constraints(List.of(),2,false,null,List.of()),input.reserve());
        assertThat(generator.generate(fixed).days().getFirst().items().stream().filter(i->i.type().equals("VISIT"))).hasSize(2);
    }
    // 섬(장소 90)이 빠지면 그 자리를 예비 관광지로 채운다. 쓰지 않은 예비 관광지는 결과에 적지 않는다.
    @Test void reservePlaceFillsTheSlotOfAnUnreachableAttraction() {
        when(maps.travelTime(any(),any(),any())).thenAnswer(invocation->{
            KakaoMapClient.Location a=invocation.getArgument(0), b=invocation.getArgument(1);
            boolean island=Math.max(a.longitude(),b.longitude())>128.885;
            return new KakaoMapClient.TravelTime(invocation.getArgument(2),island?7300:601,3000,Instant.now());
        });
        var base=input(1,LocalTime.of(18,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),1,false,
            List.of(place(1),place(90),place(2)),List.of(),base.places().restaurants());
        var input=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60,null,null,
            List.of(place(5),place(6)));
        var result=generator.generate(input);
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,2L,5L);
        assertThat(result.skipped()).extracting(PlaceRef::placeId).containsExactly(90L);
        generator.validate(input,result);
        // 예비 관광지가 아닌 곳을 넣은 결과는 받지 않는다.
        var forged=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60);
        assertThatThrownBy(()->generator.validate(forged,result)).isInstanceOf(RecommendationException.class);
    }
    // 해수욕장 두 곳이 붙어 있어도 바로 이어서 가지 않고, 조금 먼 다른 종류(전망대)를 먼저 넣는다. 날이 바뀌어도 이어서 본다.
    @Test void sameKindIsNotVisitedBackToBackWhenAnotherKindIsNearby() {
        var beachA=new PlaceCandidate(1L,"해변A","강원 강릉시 경포로 1","여행 > 관광,명소 > 해수욕장,해변","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var beachB=new PlaceCandidate(3L,"해변B","강원 강릉시 경포로 3","여행 > 관광,명소 > 해수욕장,해변","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var view=new PlaceCandidate(4L,"전망대","강원 강릉시 경포로 4","여행 > 관광,명소 > 전망대","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var base=input(1,LocalTime.of(18,0));
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),1,false,
            List.of(beachA,beachB,view),List.of(),List.of(place(30)));
        var input=new Input(base.recommendationId(),pool,base.conditions(),base.start(),base.end(),base.transport(),60);
        var result=generator.generate(input);
        // 해변B(해변A에서 2단위)가 전망대(3단위)보다 가깝지만, 같은 종류라 두 배(4단위)로 쳐서 전망대를 먼저 간다.
        assertThat(result.days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).map(Item::placeId)).containsExactly(1L,4L,3L);
        assertThat(result.days().getFirst().items().stream().filter(i->i.placeId()!=null && i.placeId()==4L && i.type().equals("VISIT")).findFirst().orElseThrow().note())
            .contains("바로 앞 방문지와 같은 종류(해변)가 이어지지 않게 골랐어요.");
        generator.validate(input,result);
    }
    // 필수 방문 장소는 시간이 부족해도 빼지 않고, 어느 장소 때문인지 알린다.
    @Test void requiredPlaceThatDoesNotFitFailsWithItsName() {
        var base=input(1,LocalTime.of(13,0));
        var input=new Input(base.recommendationId(),base.places(),base.conditions(),base.start(),base.end(),base.transport(),90,null,
            new Constraints(List.of(2L),null,false,null,List.of()));
        assertThatThrownBy(()->generator.generate(input)).isInstanceOfSatisfying(RecommendationException.class,e->{
            assertThat(e.getCode()).isEqualTo("TIME_OVERLAP"); assertThat(e.getMessage()).contains("장소2"); });
    }
    // 하루가 길고 체류시간이 짧으면 하루에 여러 곳을 넣고, 17시가 지나면 다른 음식점에서 저녁도 넣는다.
    @Test void longDayFitsMoreAttractionsAndAddsDinner() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),1,null,2,false,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),1,false,
            List.of(place(1),place(2),place(3),place(4),place(5),place(6)),List.of(),List.of(place(20),place(21)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(20,0),KakaoMapClient.Transport.CAR,60);
        var result=generator.generate(input);
        var items=result.days().getFirst().items();
        assertThat(items.stream().filter(i->i.type().equals("VISIT"))).hasSize(6);
        var meals=items.stream().filter(i->i.type().equals("MEAL")).toList();
        assertThat(meals).extracting(Item::placeId).containsExactly(20L,21L);
        assertThat(meals.get(1).start()).isAfterOrEqualTo(LocalTime.of(17,0));
        assertThat(meals.get(1).note()).startsWith("저녁 식사");
        assertThat(result.skipped()).isEmpty();
        generator.validate(input,result);
    }
    // 가장 가까운 음식점보다 2km 안쪽으로만 더 멀면, 이미 먹은 음식과 다른 분류를 먼저 고르고 그 점을 이유로 적는다.
    @Test void mealPrefersFoodNotEatenYetAndSaysWhy() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),1,null,2,false,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),1,false,
            List.of(place(1),place(2),place(3),place(4),place(5),place(6)),List.of(),
            List.of(restaurant(20,"음식점 > 한식"),restaurant(21,"음식점 > 한식"),restaurant(30,"음식점 > 중식")));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(20,0),KakaoMapClient.Transport.CAR,60);
        var result=generator.generate(input);
        var meals=result.days().getFirst().items().stream().filter(i->i.type().equals("MEAL")).toList();
        // 저녁은 더 가까운 한식(21) 대신 약 0.8km 더 먼 중식(30)이다.
        assertThat(meals).extracting(Item::placeId).containsExactly(20L,30L);
        assertThat(meals.get(0).note()).startsWith("식사 · 한식").contains("위치: 장소").doesNotContain("고른 이유");
        assertThat(meals.get(1).note()).startsWith("저녁 식사 · 중식").contains("고른 이유: 앞서 먹은 음식(한식)과 다른 분류예요.");
        generator.validate(input,result);
    }
    // 지역 대표 음식이 상호에 들어간 곳을 먼저 고르고, 가까운 후보가 없어 먼 곳을 넣었으면 그렇다고 알린다.
    @Test void mealNotesRegionalFoodAndFarDistance() {
        var tofu=new PlaceCandidate(15L,"초당두부마을","강원 강릉시 경포로 15","음식점 > 한식","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var near=dayTrip(List.of(place(1),place(2)),List.of(restaurant(3,"음식점 > 한식"),tofu),90,List.of());
        var meal=generator.generate(near).days().getFirst().items().stream().filter(i->i.type().equals("MEAL")).findFirst().orElseThrow();
        assertThat(meal.placeId()).isEqualTo(15L);
        assertThat(meal.note()).contains("고른 이유: 상호나 분류에 이 지역 대표 음식(초당두부)이 들어간 곳이에요.");
        // 음식점 300은 관광지에서 약 26km 떨어져 있다.
        var far=dayTrip(List.of(place(1),place(2)),List.of(restaurant(300,"음식점 > 한식")),90,List.of());
        var farMeal=generator.generate(far).days().getFirst().items().stream().filter(i->i.type().equals("MEAL")).findFirst().orElseThrow();
        assertThat(farMeal.note()).contains("떨어져 있어요. 더 가까운 곳에 확인된 음식점 후보가 없었어요.");
    }
    // 관광지에는 고른 여행 취향에 맞는 분류라는 이유를 적고, 취향에 맞지 않는 곳은 후보가 모자라 채운 곳이라고 알린다.
    @Test void visitNoteShowsMatchedThemeInsteadOfDistance() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),1,null,2,false,false,
            List.of(new TravelKeyword(1L,"THEME","바다",null)),"");
        var beach=new PlaceCandidate(1L,"해변A","강원 강릉시 경포로 1","여행 > 관광,명소 > 해수욕장,해변","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var shop=new PlaceCandidate(2L,"시장","강원 강릉시 경포로 2","쇼핑 > 전통시장","","https://www.gn.go.kr/","",LocalDateTime.of(2026,9,1,0,0));
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),1,false,
            List.of(beach,shop),List.of(),List.of(place(3)));
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var visits=generator.generate(input).days().getFirst().items().stream().filter(i->i.type().equals("VISIT")).toList();
        assertThat(visits.get(0).note()).isEqualTo("관광·관람 · 해수욕장·해변\n고른 이유: 고른 여행 취향(바다)에 맞는 분류예요.");
        assertThat(visits.get(1).note()).isEqualTo("관광·관람 · 전통시장\n고른 이유: 고른 취향에 맞는 관광지 후보가 모자라 이 지역의 다른 종류 관광지로 채운 곳이에요.");
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
