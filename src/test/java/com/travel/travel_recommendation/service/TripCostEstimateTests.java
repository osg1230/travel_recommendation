package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.TripCostEstimate.Line;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TripCostEstimateTests {
    private static final TravelKeyword COUPLE = new TravelKeyword(13L, "COMPANION", "연인", "{\"value\":\"COUPLE\"}");
    private static final TravelKeyword FRIENDS = new TravelKeyword(12L, "COMPANION", "친구", "{\"value\":\"FRIENDS\"}");

    private static Input input(Integer partySize, List<TravelKeyword> keywords) {
        return input(partySize, keywords, null, 1, false, null);
    }
    private static Input input(Integer partySize, List<TravelKeyword> keywords, BigDecimal budget, int days, boolean stay, HomeTrip home) {
        var conditions = new ConfirmedTravelConditions("TEXT", "", "여수", null, null, days, budget, partySize, stay, null, keywords, "");
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(1L, "여수", "전남광주통합특별시 여수시", "", "https://www.yeosu.go.kr/tour", LocalDateTime.now()),
            days, stay, List.of(), List.of(), List.of());
        return new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90,
            null, null, null, null, home);
    }
    // 점심 1끼(음식점 7, 1인분 13,000원)만 있는 하루 일정.
    private static Result result() {
        var meal = new Item("MEAL", 7L, "동서식당", LocalTime.of(12, 0), LocalTime.of(13, 0), "", "", "", null, null);
        var quote = new PriceQuote(7L, "동서식당", OpenAiPriceClient.RESTAURANT, "VERIFIED", "서대회 13,000원", 13000, 13000, "서대회 1인분", "https://menu.example.kr/7");
        return new Result("여수", List.of(new Day(1, null, List.of(meal))), KakaoMapClient.Transport.CAR, Instant.now())
            .withPrices(new Prices(List.of(quote), Instant.now()));
    }
    private static Line line(TripCostEstimate estimate, String name) {
        return estimate.lines().stream().filter(l -> l.name().startsWith(name)).findFirst().orElseThrow();
    }

    // 인원을 적지 않아도 동행이 '연인'이면 2명으로 추정하고, 그 근거를 함께 보여 준다.
    @Test void partySizeComesFromInputThenCompanionThenPerPerson() {
        var couple = TripCostEstimate.of(input(null, List.of(COUPLE)), result());
        assertThat(couple.partySize()).isEqualTo(2);
        assertThat(couple.partyBasis()).contains("연인");
        assertThat(line(couple, "식비").min()).isEqualTo(26000);
        // 입력한 인원이 있으면 동행보다 먼저 쓴다.
        var entered = TripCostEstimate.of(input(3, List.of(COUPLE)), result());
        assertThat(entered.partySize()).isEqualTo(3); assertThat(entered.partyBasis()).isEqualTo("입력한 인원");
        // '친구'처럼 인원을 알 수 없으면 1인 기준이라고 밝힌다.
        var friends = TripCostEstimate.of(input(null, List.of(FRIENDS)), result());
        assertThat(friends.partySize()).isEqualTo(1); assertThat(friends.partyBasis()).contains("1인 기준");
    }

    // 참고 가격을 찾지 않아도 기준 범위로 식비·시내 이동·카페를 추정하고, 집 이동이 없으면 왕복 교통은 뺐다고 알린다.
    @Test void estimatesFromReferenceRangesWhenNoPricesFound() {
        var estimate = TripCostEstimate.of(input(2, List.of()), result().withPrices(null));
        assertThat(estimate).isNotNull();
        var meals = line(estimate, "식비");
        assertThat(meals.min()).isEqualTo(TripCostEstimate.MEAL_MIN * 2);
        assertThat(meals.max()).isEqualTo(TripCostEstimate.MEAL_MAX * 2);
        assertThat(meals.basis()).contains("1끼 × 2명").doesNotContain("참고 가격을 찾은");
        // 자동차 일정의 시내 이동은 차량 1대 기준이라 인원을 곱하지 않는다.
        assertThat(line(estimate, "시내 이동").min()).isEqualTo(TripCostEstimate.CAR_LOCAL_MIN);
        assertThat(line(estimate, "카페").max()).isEqualTo(TripCostEstimate.CAFE_MAX * 2);
        assertThat(estimate.lines()).noneMatch(l -> l.name().startsWith("왕복 교통"));
        assertThat(estimate.excluded()).anyMatch(reason -> reason.contains("집 출발·귀가"));
        assertThat(estimate.nights()).isZero();
        assertThat(estimate.budgetNote()).isNull();
        assertThat(estimate.totalMin()).isEqualTo(estimate.lines().stream().mapToInt(Line::min).sum());
        assertThat(estimate.perPersonMin()).isEqualTo((estimate.totalMin() / 2 + 500) / 1000 * 1000);
    }

    // 1박 숙소는 찾은 참고 가격이 있으면 그 값 × 박 수, 없으면 숙소 유형(펜션은 '보통')의 기준 범위를 쓴다.
    @Test void lodgingUsesFoundPriceOrStayTierRange() {
        var stayItem = new Item("STAY", 20L, "솔숲 펜션", LocalTime.of(18, 0), LocalTime.of(18, 0), "", "", "", null, null);
        var visit = new Item("VISIT", 30L, "오동도", LocalTime.of(10, 0), LocalTime.of(11, 30), "", "", "", null, null);
        var lodging = new Lodging(20L, "가까워요", List.of(new StayOption(20L, "솔숲 펜션", "여행 > 숙박 > 펜션", "여수시", 1.2, "", "")));
        var days = List.of(new Day(1, null, List.of(visit, stayItem)), new Day(2, null, List.of(visit)));
        var base = new Result("여수", days, KakaoMapClient.Transport.CAR, Instant.now()).withLodging(lodging);
        var in = input(2, List.of(), null, 2, true, null);

        var byTier = TripCostEstimate.of(in, base);
        assertThat(byTier.nights()).isEqualTo(1);
        var stay = line(byTier, "숙박");
        assertThat(stay.min()).isEqualTo(TripCostEstimate.STAY_PER_NIGHT.get(BudgetGuide.StayTier.STANDARD)[0]);
        assertThat(stay.basis()).contains("'보통'").contains("1박");
        assertThat(line(byTier, "관광").max()).isEqualTo(2 * 2 * TripCostEstimate.ADMISSION_MAX);

        var quote = new PriceQuote(20L, "솔숲 펜션", OpenAiPriceClient.LODGING, "VERIFIED", "1박 120,000원", 120000, 150000, "2인 기준", "https://stay.example.kr/20");
        var byPrice = TripCostEstimate.of(in, base.withPrices(new Prices(List.of(quote), Instant.now())));
        assertThat(line(byPrice, "숙박").min()).isEqualTo(120000);
        assertThat(line(byPrice, "숙박").max()).isEqualTo(150000);
        assertThat(line(byPrice, "숙박").basis()).contains("찾은 참고 가격");
        // 5명이면 객실 2개로 계산한다.
        var five = TripCostEstimate.of(input(5, List.of(), null, 2, true, null), base);
        assertThat(line(five, "숙박").basis()).contains("객실 2개");
    }

    // 집 출발·귀가를 정했으면 편도 시간으로 왕복 교통비를 추정한다. 자동차는 차량 1대, 대중교통은 인원만큼, 비행기는 편도 고정 범위다.
    @Test void intercityTransportFollowsHomeTripAndTransport() {
        var home = new HomeTrip("청주", LocalTime.of(8, 0), LocalTime.of(20, 0), 120, "자동차 · 카카오 길찾기", Instant.now());
        var car = TripCostEstimate.of(input(3, List.of(), null, 1, false, home), result().withPrices(null));
        var carLine = line(car, "왕복 교통(자동차)");
        int km = 240 * TripCostEstimate.CAR_KM_PER_HOUR / 60;
        assertThat(carLine.min()).isEqualTo((km * TripCostEstimate.CAR_WON_PER_KM_MIN + 500) / 1000 * 1000);
        assertThat(carLine.basis()).contains("차량 1대");

        var transitResult = new Result("여수", result().days(), KakaoMapClient.Transport.PUBLIC_TRANSIT, Instant.now());
        var transit = TripCostEstimate.of(input(3, List.of(), null, 1, false, home), transitResult);
        var transitLine = line(transit, "왕복 교통(대중교통)");
        assertThat(transitLine.min()).isEqualTo((240 * TripCostEstimate.TRANSIT_WON_PER_MINUTE_MIN * 3 + 500) / 1000 * 1000);
        assertThat(transitLine.basis()).contains("× 3명");
        // 대중교통 시내 이동은 인원을 곱한다.
        assertThat(line(transit, "시내 이동").min()).isEqualTo(TripCostEstimate.TRANSIT_LOCAL_MIN * 3);

        var flightHome = new HomeTrip("서울 강서구", LocalTime.of(8, 0), LocalTime.of(20, 0), 150, "비행기 · 김포 → 제주 약 65분(오늘 운항 기준)", Instant.now());
        var flight = TripCostEstimate.of(input(1, List.of(), null, 1, false, flightHome), transitResult);
        assertThat(line(flight, "왕복 교통").min()).isEqualTo(TripCostEstimate.FLIGHT_MIN * 2);
        assertThat(line(flight, "왕복 교통").basis()).contains("비행기");
    }

    // 입력한 예산은 추정 범위와 비교한 안내만 붙이고, 1인 기준 예산이면 인원을 곱해 비교한다.
    @Test void budgetNoteComparesEnteredBudgetWithRange() {
        var small = TripCostEstimate.of(input(2, List.of(), new BigDecimal("10000"), 1, false, null), result().withPrices(null));
        assertThat(small.budgetNote()).contains("10,000원").contains("적어요");
        var large = TripCostEstimate.of(input(2, List.of(), new BigDecimal("9000000"), 1, false, null), result().withPrices(null));
        assertThat(large.budgetNote()).contains("많아요");
        var inside = TripCostEstimate.of(input(2, List.of(), BigDecimal.valueOf(small.totalMin() + 1000), 1, false, null), result().withPrices(null));
        assertThat(inside.budgetNote()).contains("범위 안");
        // 권장 계획 예산은 중간값을 만 원 단위로 올린다.
        assertThat(inside.planningWon() % 10_000).isZero();
        assertThat(inside.planningWon()).isGreaterThanOrEqualTo((inside.totalMin() + inside.totalMax()) / 2);
    }
}
