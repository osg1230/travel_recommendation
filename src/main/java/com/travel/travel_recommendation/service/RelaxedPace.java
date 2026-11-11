package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 여유 있는 일정을 원하는지 판단하고 일정 조건에 반영한다(2026-11-11).
 * 여행 목적이나 처음 입력한 문장에 "여유롭게", "빡빡하지 않게" 같은 말이 있으면 여유 일정으로 본다(PetTravel과 같은 방식).
 * 예전에는 이런 말이 여행 목적 문장에만 남아, 하루 4곳을 채우고 남는 시간에 관광지를 더 넣었다.
 * 낱말로 찾기 때문에 "예산은 여유 있게"처럼 일정 속도가 아닌 말도 여유 일정으로 볼 수 있다.
 */
public final class RelaxedPace {
    private RelaxedPace() {}
    private static final Pattern RELAXED = Pattern.compile(
        "여유롭|여유로|여유\\s?있게|느긋|빡빡하지\\s?않|안\\s?빡빡|쉬엄쉬엄|천천히|무리하지\\s?않|무리\\s?없이|널널");

    /** 여유 일정의 하루 관광지 수(저녁 관광 포함). 따로 정하지 않았을 때의 상한 4곳보다 한 곳 적다. */
    public static final int DAILY_VISITS = 3;
    /** 여유 일정에서 바다 취향을 골랐을 때 해변 한 곳에 머무는 시간(분). 물놀이·모래놀이와 씻는 시간까지 본 값이다. */
    public static final int BEACH_MINUTES = 120;

    public static boolean wanted(ConfirmedTravelConditions conditions) {
        return conditions != null && (mentions(conditions.travelPurpose()) || mentions(conditions.originalText()));
    }

    static boolean mentions(String text) { return text != null && RELAXED.matcher(text).find(); }

    /**
     * 여유 일정인데 하루 방문 수를 정하지 않았으면 하루 DAILY_VISITS곳을 선호 조건으로 넣는다.
     * 하루 방문 수가 있으면 일정 생성이 그 수까지만 넣고 남는 시간을 관광지로 채우지 않는다.
     * 사용자가 하루 방문 수를 직접 정했으면 그 값을 그대로 둔다.
     */
    public static Constraints apply(Constraints constraints, ConfirmedTravelConditions conditions) {
        if (!wanted(conditions)) return constraints;
        if (constraints == null) return new Constraints(List.of(), DAILY_VISITS, false, null, List.of());
        if (constraints.dailyVisits() != null) return constraints;
        return new Constraints(constraints.requiredPlaceIds(), DAILY_VISITS, false, constraints.budgetBasis(), constraints.budgetIncludes(),
            constraints.stayTier(), constraints.centerPlaceIds(), constraints.festival());
    }

    /**
     * 낮 관광지의 체류시간(분). 보통은 종류별 체류시간(VisitMinutes)이고,
     * 여유 일정에서 바다 취향을 골랐으면 해변은 잠깐 들르는 60분 대신 BEACH_MINUTES분으로 잡는다.
     */
    public static int visitMinutes(ConfirmedTravelConditions conditions, PlaceCandidate place, int base) {
        int minutes = VisitMinutes.of(place, base);
        boolean beach = "해변".equals(TripConstraints.kind(place));
        if (beach && wanted(conditions) && conditions.themeNames().contains("바다")) return Math.max(minutes, BEACH_MINUTES);
        return minutes;
    }
}
