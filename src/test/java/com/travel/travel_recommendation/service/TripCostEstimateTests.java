package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TripCostEstimateTests {
    private static final TravelKeyword COUPLE = new TravelKeyword(13L, "COMPANION", "연인", "{\"value\":\"COUPLE\"}");
    private static final TravelKeyword FRIENDS = new TravelKeyword(12L, "COMPANION", "친구", "{\"value\":\"FRIENDS\"}");

    private static Input input(Integer partySize, List<TravelKeyword> keywords) {
        var conditions = new ConfirmedTravelConditions("TEXT", "", "여수", null, null, 1, null, partySize, false, null, keywords, "");
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(1L, "여수", "전남광주통합특별시 여수시", "", "https://www.yeosu.go.kr/tour", LocalDateTime.now()),
            1, false, List.of(), List.of(), List.of());
        return new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90);
    }
    // 점심 1끼(음식점 7, 1인분 13,000원)만 있는 하루 일정.
    private static Result result() {
        var meal = new Item("MEAL", 7L, "동서식당", LocalTime.of(12, 0), LocalTime.of(13, 0), "", "", "", null, null);
        var quote = new PriceQuote(7L, "동서식당", OpenAiPriceClient.RESTAURANT, "VERIFIED", "서대회 13,000원", 13000, 13000, "서대회 1인분", "https://menu.example.kr/7");
        return new Result("여수", List.of(new Day(1, null, List.of(meal))), KakaoMapClient.Transport.CAR, Instant.now())
            .withPrices(new Prices(List.of(quote), Instant.now()));
    }

    // 인원을 적지 않아도 동행이 '연인'이면 2명으로 추정하고, 그 근거를 함께 보여 준다.
    @Test void partySizeComesFromInputThenCompanionThenPerPerson() {
        var couple = TripCostEstimate.of(input(null, List.of(COUPLE)), result());
        assertThat(couple.partySize()).isEqualTo(2);
        assertThat(couple.partyBasis()).contains("연인");
        assertThat(couple.mealMin()).isEqualTo(26000);
        // 입력한 인원이 있으면 동행보다 먼저 쓴다.
        var entered = TripCostEstimate.of(input(3, List.of(COUPLE)), result());
        assertThat(entered.partySize()).isEqualTo(3); assertThat(entered.partyBasis()).isEqualTo("입력한 인원");
        // '친구'처럼 인원을 알 수 없으면 1인 기준이라고 밝힌다.
        var friends = TripCostEstimate.of(input(null, List.of(FRIENDS)), result());
        assertThat(friends.partySize()).isEqualTo(1); assertThat(friends.partyBasis()).contains("1인 기준");
        assertThat(TripCostEstimate.of(input(null, List.of()), result().withPrices(null))).isNull();
    }
}
