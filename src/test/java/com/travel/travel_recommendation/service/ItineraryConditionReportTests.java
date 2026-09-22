package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.ItineraryConditionReport.Status;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ItineraryConditionReportTests {
    private final KakaoMapClient maps=mock(KakaoMapClient.class);
    private final ItineraryGenerator generator=new ItineraryGenerator(maps);
    private PlaceCandidate place(long id) {
        return new PlaceCandidate(id,"장소"+id,"강원 강릉시 경포로 "+id,"유형","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
    }
    private Input input(int days,LocalDate start,BigDecimal budget) {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start==null?null:start.plusDays(days-1),days,budget,2,days>1,false,List.of(),"");
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),days,days>1,
            List.of(place(1),place(2)),days>1?List.of(place(4)):List.of(),List.of(place(3)));
        return new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
    }
    private static Status status(ItineraryConditionReport.Report report,String name) {
        return report.checks().stream().filter(c->c.name().equals(name)).findFirst().orElseThrow().status();
    }
    @BeforeEach void setup() {
        when(maps.locate(anyString())).thenAnswer(invocation->{
            String address=invocation.getArgument(0); int id=Integer.parseInt(address.substring(address.lastIndexOf(' ')+1));
            return new KakaoMapClient.Location(address,128.8+id*0.001,37.7,Instant.now());
        });
        when(maps.travelTime(any(),any(),any())).thenAnswer(invocation->new KakaoMapClient.TravelTime(invocation.getArgument(2),601,3000,Instant.now()));
    }

    @Test void generatedPlanIsCompleteButUnverifiableItemsStayNeedsCheck() {
        var input=input(2,LocalDate.of(2026,10,1),new BigDecimal("300000"));
        var report=ItineraryConditionReport.evaluate(input,generator.generate(input));
        assertThat(report.complete()).isTrue();
        assertThat(status(report,"여행 기간")).isEqualTo(Status.MET);
        assertThat(status(report,"선택한 관광지 포함")).isEqualTo(Status.MET);
        assertThat(status(report,"방문 중복")).isEqualTo(Status.MET);
        assertThat(status(report,"시간 충돌")).isEqualTo(Status.MET);
        assertThat(status(report,"이동시간")).isEqualTo(Status.MET);
        // 운영정보·가격 자료가 없으면 충족으로 확정하지 않는다.
        assertThat(status(report,"운영시간·휴무")).isEqualTo(Status.NEEDS_CHECK);
        assertThat(status(report,"예산")).isEqualTo(Status.NEEDS_CHECK);
        assertThat(status(report,"숙박")).isEqualTo(Status.NEEDS_CHECK);
        assertThat(report.count(Status.UNMET)).isZero();
    }
    @Test void walkingEstimatedMovesAreShownAsEstimateAndNeedCheck() {
        when(maps.travelTime(any(),any(),any())).thenAnswer(invocation->new KakaoMapClient.TravelTime(invocation.getArgument(2),150,130,Instant.now(),"도보 약 3분 · 추정",true));
        var input=input(1,LocalDate.of(2026,10,1),null);
        var result=generator.generate(input);
        generator.validate(input,result);
        var moves=result.days().getFirst().items().stream().filter(i->i.type().equals("TRANSIT")).toList();
        assertThat(moves).isNotEmpty().allSatisfy(i->{
            assertThat(i.note()).startsWith("도보 추정 3분 + 이동 여유 10분").doesNotContain("카카오 예상").doesNotContain("배차·요금");
            assertThat(Duration.between(i.start(),i.end()).toMinutes()).isEqualTo(13);
        });
        assertThat(status(ItineraryConditionReport.evaluate(input,result),"이동시간")).isEqualTo(Status.NEEDS_CHECK);
    }
    @Test void missingDateAndBudgetAreNotTreatedAsViolations() {
        var input=input(1,null,null);
        var report=ItineraryConditionReport.evaluate(input,generator.generate(input));
        assertThat(report.complete()).isTrue();
        assertThat(status(report,"여행 기간")).isEqualTo(Status.NEEDS_CHECK);
        assertThat(status(report,"예산")).isEqualTo(Status.NOT_APPLICABLE);
        assertThat(status(report,"숙박")).isEqualTo(Status.NOT_APPLICABLE);
    }
    @Test void reportsDuplicatedVisitMissingAttractionAndOverlapAsUnmet() {
        var input=input(1,LocalDate.of(2026,10,1),null);
        var result=generator.generate(input);
        var items=new ArrayList<>(result.days().getFirst().items());
        var second=items.stream().filter(i->i.placeId()==2L && i.type().equals("VISIT")).findFirst().orElseThrow();
        var first=items.stream().filter(i->i.placeId()==1L && i.type().equals("VISIT")).findFirst().orElseThrow();
        items.set(items.indexOf(second),new Item("VISIT",1L,first.name(),second.start(),second.end(),second.note(),second.mapUrl(),second.sourceUrl(),null,null));
        var duplicated=new Result(result.destination(),List.of(new Day(1,result.days().getFirst().date(),items)),result.transport(),result.createdAt());
        var report=ItineraryConditionReport.evaluate(input,duplicated);
        assertThat(report.complete()).isFalse();
        assertThat(status(report,"방문 중복")).isEqualTo(Status.UNMET);
        assertThat(status(report,"선택한 관광지 포함")).isEqualTo(Status.UNMET);

        var overlap=new ArrayList<>(result.days().getFirst().items());
        var last=overlap.getLast();
        overlap.set(overlap.size()-1,new Item(last.type(),last.placeId(),last.name(),last.start(),LocalTime.of(19,0),last.note(),last.mapUrl(),last.sourceUrl(),last.travelSeconds(),last.checkedAt()));
        var late=new Result(result.destination(),List.of(new Day(1,result.days().getFirst().date(),overlap)),result.transport(),result.createdAt());
        assertThat(status(ItineraryConditionReport.evaluate(input,late),"시간 충돌")).isEqualTo(Status.UNMET);
    }
    @Test void requiredPlacesAndDailyVisitsFollowRequiredOrPreferred() {
        var base=input(2,LocalDate.of(2026,10,1),new BigDecimal("300000"));
        var result=generator.generate(base);
        var required=new Input(base.recommendationId(),base.places(),base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes(),null,
            new Constraints(List.of(2L),1,true,"PER_PERSON",List.of("MEAL","LODGING")));
        var report=ItineraryConditionReport.evaluate(required,result);
        assertThat(status(report,"필수 방문 장소")).isEqualTo(Status.MET);
        assertThat(status(report,"하루 방문 수")).isEqualTo(Status.MET);
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("예산"); assertThat(c.detail()).contains("1인 기준").contains("식사").contains("숙박"); });
        // 하루 2곳을 필수로 요구했는데 1곳씩이면 미충족, 선호였다면 확인 필요로 구분한다.
        var strict=new Input(base.recommendationId(),base.places(),base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes(),null,
            new Constraints(List.of(),2,true,null,List.of()));
        assertThat(status(ItineraryConditionReport.evaluate(strict,result),"하루 방문 수")).isEqualTo(Status.UNMET);
        var soft=new Input(base.recommendationId(),base.places(),base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes(),null,
            new Constraints(List.of(),2,false,null,List.of()));
        assertThat(status(ItineraryConditionReport.evaluate(soft,result),"하루 방문 수")).isEqualTo(Status.NEEDS_CHECK);
        assertThat(ItineraryConditionReport.evaluate(soft,result).complete()).isTrue();
        assertThat(status(ItineraryConditionReport.evaluate(base,result),"필수 방문 장소")).isEqualTo(Status.NOT_APPLICABLE);
    }
    @Test void missingRequiredPlaceIsUnmet() {
        var base=input(1,LocalDate.of(2026,10,1),null);
        var result=generator.generate(base);
        var items=result.days().getFirst().items().stream().filter(i->!(i.type().equals("VISIT") && i.placeId()==2L)).toList();
        var withoutTwo=new Result(result.destination(),List.of(new Day(1,result.days().getFirst().date(),items)),result.transport(),result.createdAt());
        var required=new Input(base.recommendationId(),base.places(),base.conditions(),base.start(),base.end(),base.transport(),base.visitMinutes(),null,
            new Constraints(List.of(2L),null,false,null,List.of()));
        assertThat(status(ItineraryConditionReport.evaluate(required,withoutTwo),"필수 방문 장소")).isEqualTo(Status.UNMET);
    }
    @Test void periodMismatchIsUnmet() {
        var input=input(2,LocalDate.of(2026,10,1),null);
        var result=generator.generate(input);
        var shortened=new Result(result.destination(),List.of(result.days().getFirst()),result.transport(),result.createdAt());
        var report=ItineraryConditionReport.evaluate(input,shortened);
        assertThat(status(report,"여행 기간")).isEqualTo(Status.UNMET);
        assertThat(report.complete()).isFalse();
    }
}
