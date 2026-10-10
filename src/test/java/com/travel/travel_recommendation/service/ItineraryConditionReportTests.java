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
    // 고른 여행 취향마다 맞는 관광지가 일정에 있는지 보여 준다. 선호 조건이라 없으면 미충족이 아니라 확인 필요다.
    @Test void travelThemesAreReportedAsPreferenceByPlaceCategory() {
        var base=input(1,LocalDate.of(2026,10,1),null);
        assertThat(status(ItineraryConditionReport.evaluate(base,generator.generate(base)),"여행 취향")).isEqualTo(Status.NOT_APPLICABLE);
        var culture=new TravelKeyword(30L,"THEME","문화·역사","{}"); var sea=new TravelKeyword(27L,"THEME","바다","{}");
        var shrine=new PlaceCandidate(1L,"경기전","강원 강릉시 경포로 1","여행 > 관광,명소 > 문화유적","소개","https://www.gn.go.kr/","https://map.kakao.com/",LocalDateTime.of(2026,9,1,0,0));
        var c=base.conditions();
        var conditions=new ConfirmedTravelConditions(c.inputMode(),c.originalText(),c.region(),c.startDate(),c.endDate(),c.durationDays(),c.budget(),
            c.partySize(),c.accommodationNeeded(),c.includeNearby(),List.of(culture,sea),c.interpretationSummary());
        var pool=new PlaceCandidateView(base.places().jobId(),base.places().destination(),1,false,List.of(shrine,place(2)),List.of(),base.places().restaurants());
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        var report=ItineraryConditionReport.evaluate(input,generator.generate(input));
        var check=report.checks().stream().filter(x->x.name().equals("여행 취향")).findFirst().orElseThrow();
        assertThat(check.status()).isEqualTo(Status.NEEDS_CHECK);
        assertThat(check.detail()).contains("문화·역사 1곳(경기전)").contains("바다에 맞는 관광지는 확인된 후보에서 찾지 못했어요");
        assertThat(report.complete()).isTrue();
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
            new Constraints(List.of(2L),1,true,null,List.of()));
        var report=ItineraryConditionReport.evaluate(required,result);
        assertThat(status(report,"필수 방문 장소")).isEqualTo(Status.MET);
        assertThat(status(report,"하루 방문 수")).isEqualTo(Status.MET);
        assertThat(report.checks()).anySatisfy(c->{ assertThat(c.name()).isEqualTo("예산"); assertThat(c.detail()).contains("300,000원").contains("예상 경비").contains("직접 확인"); });
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
    // 반려동물 동반 여행이 아니면 해당 없음, 동반 여행이면 관광공사 정보가 모두 있고 불가가 없을 때만 충족이다. 미충족은 쓰지 않는다.
    @Test void petTravelIsMetOnlyWhenEveryPlaceHasAllowedInfo() {
        var base=input(1,LocalDate.of(2026,10,1),null);
        var result=generator.generate(base);
        assertThat(status(ItineraryConditionReport.evaluate(base,result),"반려동물 동반")).isEqualTo(Status.NOT_APPLICABLE);
        var c=base.conditions();
        var pet=new ConfirmedTravelConditions(c.inputMode(),"강아지랑 같이 가요",c.region(),c.startDate(),c.endDate(),c.durationDays(),c.budget(),
            c.partySize(),c.accommodationNeeded(),c.includeNearby(),c.selectedKeywords(),c.interpretationSummary());
        var input=new Input(base.recommendationId(),base.places(),pet,base.start(),base.end(),base.transport(),base.visitMinutes());
        assertThat(status(ItineraryConditionReport.evaluate(input,result),"반려동물 동반")).isEqualTo(Status.NEEDS_CHECK);
        var allowed=new PlacePetInfo("1","전구역 동반가능","전 견종","목줄 착용","",Instant.now());
        var partial=new PlacePetInfo("2","일부구역 동반가능","소형견","","",Instant.now());
        var met=ItineraryConditionReport.evaluate(input,result.withPets(List.of(new PetCheck(1L,"장소1",allowed),new PetCheck(2L,"장소2",partial))));
        assertThat(status(met,"반려동물 동반")).isEqualTo(Status.MET);
        assertThat(met.checks().stream().filter(k->k.name().equals("반려동물 동반")).findFirst().orElseThrow().detail()).contains("동반 가능 1곳","일부 구역만 가능 1곳");
        var mixed=ItineraryConditionReport.evaluate(input,result.withPets(List.of(new PetCheck(1L,"장소1",allowed),
            new PetCheck(2L,"장소2",new PlacePetInfo("2","동반불가","","","",Instant.now())),new PetCheck(3L,"장소3",null))));
        var check=mixed.checks().stream().filter(k->k.name().equals("반려동물 동반")).findFirst().orElseThrow();
        assertThat(check.status()).isEqualTo(Status.NEEDS_CHECK);
        assertThat(check.detail()).contains("동반 불가로 안내된 곳: 장소2","정보가 없는 곳 1곳(장소3)","동반 가능 1곳");
        assertThat(mixed.complete()).isTrue();
        // AI 웹 검색 결과가 있으면 요약을 덧붙이되 상태는 확인 필요 그대로다.
        var searched=ItineraryConditionReport.evaluate(input,mixed.checks().isEmpty()?result:result.withPets(List.of(new PetCheck(3L,"장소3",null)))
            .withVerifications(new Verifications(List.of(new CheckAnswer(3L,"장소3","PET","q","YES","가능해요","https://a.kr/","VERIFIED",Instant.now())),Instant.now())));
        var withAi=searched.checks().stream().filter(k->k.name().equals("반려동물 동반")).findFirst().orElseThrow();
        assertThat(withAi.status()).isEqualTo(Status.NEEDS_CHECK);
        assertThat(withAi.detail()).contains("AI 웹 검색 1곳: 가능 1곳(장소3)","출처 페이지에서 장소 이름을 확인한 답 1곳");
    }
    // 직접 말한 여행 목적 활동은 늘 확인 필요이고, AI 검색 결과가 있으면 가능·불가·못 찾음 수를 덧붙인다. 제안한 방향이면 해당 없음이다.
    @Test void purposeActivityStaysNeedsCheckWithAiSearchSummary() {
        var base=input(1,LocalDate.of(2026,10,1),null);
        var result=generator.generate(base);
        assertThat(status(ItineraryConditionReport.evaluate(base,result),"여행 목적 활동")).isEqualTo(Status.NOT_APPLICABLE);
        var c=base.conditions();
        var purpose=new ConfirmedTravelConditions(c.inputMode(),c.originalText(),c.region(),c.startDate(),c.endDate(),c.durationDays(),c.budget(),
            c.partySize(),c.accommodationNeeded(),c.includeNearby(),c.selectedKeywords(),c.interpretationSummary(),true,"상어 관람");
        var input=new Input(base.recommendationId(),base.places(),purpose,base.start(),base.end(),base.transport(),base.visitMinutes());
        var plain=ItineraryConditionReport.evaluate(input,result).checks().stream().filter(k->k.name().equals("여행 목적 활동")).findFirst().orElseThrow();
        assertThat(plain.status()).isEqualTo(Status.NEEDS_CHECK); assertThat(plain.detail()).contains("'상어 관람'").doesNotContain("AI 웹 검색");
        var answers=new Verifications(List.of(new CheckAnswer(1L,"장소1","PURPOSE","q","NO","상어 전시는 없어요","https://a.kr/","UNVERIFIED",Instant.now()),
            new CheckAnswer(2L,"장소2","PURPOSE","q","UNKNOWN","",null,"NOT_FOUND",Instant.now())),Instant.now());
        var withAi=ItineraryConditionReport.evaluate(input,result.withVerifications(answers)).checks().stream().filter(k->k.name().equals("여행 목적 활동")).findFirst().orElseThrow();
        assertThat(withAi.status()).isEqualTo(Status.NEEDS_CHECK);
        assertThat(withAi.detail()).contains("AI 웹 검색 2곳: 가능 0곳 · 불가 1곳(장소1) · 못 찾음 1곳");
    }
}
