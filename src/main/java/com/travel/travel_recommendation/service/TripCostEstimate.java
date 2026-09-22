package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.util.*;

/**
 * 결과 화면에서 찾은 참고 가격으로 계산한 비용 범위. 숙박은 객실 1개 × 박 수, 식사는 가격을 찾은 음식점만 1인분 × 인원으로 더한다.
 * 가격을 못 찾은 곳·교통·입장료는 빠져 있어 예산 충족 판단에는 쓰지 않고 참고로만 보여 준다.
 */
public record TripCostEstimate(Integer lodgingMin, Integer lodgingMax, int nights, Integer mealMin, Integer mealMax,
                               int meals, int pricedMeals, int partySize) {

    /** 가격을 아직 찾지 않았으면 null이다. */
    public static TripCostEstimate of(Input input, Result result) {
        if (result.prices() == null) return null;
        Map<Long, PriceQuote> found = new HashMap<>();
        for (var quote : result.prices().quotes()) if (quote.found()) found.put(quote.placeId(), quote);
        int party = input.conditions().partySize() == null || input.conditions().partySize() < 1 ? 1 : input.conditions().partySize();
        int nights = result.lodging() != null && input.places().accommodationNeeded() ? result.days().size() - 1 : 0;
        var stay = result.lodging() == null ? null : found.get(result.lodging().chosenPlaceId());
        Integer lodgingMin = stay == null || nights == 0 ? null : stay.minWon() * nights;
        Integer lodgingMax = stay == null || nights == 0 ? null : stay.maxWon() * nights;
        int meals = 0, priced = 0, mealMin = 0, mealMax = 0;
        for (var day : result.days()) for (var item : day.items()) {
            if (!"MEAL".equals(item.type())) continue;
            meals++;
            var quote = found.get(item.placeId());
            if (quote == null) continue;
            priced++; mealMin += quote.minWon() * party; mealMax += quote.maxWon() * party;
        }
        return new TripCostEstimate(lodgingMin, lodgingMax, nights, priced == 0 ? null : mealMin, priced == 0 ? null : mealMax,
            meals, priced, party);
    }

    public Integer totalMin() { return sum(lodgingMin, mealMin); }
    public Integer totalMax() { return sum(lodgingMax, mealMax); }
    private static Integer sum(Integer a, Integer b) {
        if (a == null && b == null) return null;
        return (a == null ? 0 : a) + (b == null ? 0 : b);
    }
}
