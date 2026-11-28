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
 *
 * 2026-11-16부터 사용자가 고른 여행 속도(PACE 키워드: 느긋하게·보통·알차게)를 먼저 본다.
 * 속도를 골랐으면 문장의 낱말은 보지 않고, 고르지 않았을 때만 낱말로 여유 일정을 찾는다.
 * - 느긋하게: 하루 3곳, 체류시간 1.3배, 저녁 관광 없음
 * - 보통(또는 고르지 않음): 지금까지의 기준
 * - 알차게: 체류시간 0.8배, 남는 시간을 채울 때 하루 상한을 한 곳 늘린다(4곳 → 5곳)
 *   상한만 늘렸을 때는 09:00~18:00 하루가 먼저 차서 보통과 같았다(2026-11-16 비교). 그래서 머무는 시간도 줄인다.
 */
public final class RelaxedPace {
    private RelaxedPace() {}
    private static final Pattern RELAXED = Pattern.compile(
        "여유롭|여유로|여유\\s?있게|느긋|빡빡하지\\s?않|안\\s?빡빡|쉬엄쉬엄|천천히|무리하지\\s?않|무리\\s?없이|널널|많이\\s?걷지\\s?않|푹\\s?쉬|많이\\s?돌아다니지\\s?않");

    /** 여유 일정의 하루 관광지 수(저녁 관광 포함). 따로 정하지 않았을 때의 상한 4곳보다 한 곳 적다. */
    public static final int DAILY_VISITS = 3;
    /** 여유 일정에서 바다 취향을 골랐을 때 해변 한 곳에 머무는 시간(분). 물놀이·모래놀이와 씻는 시간까지 본 값이다. */
    public static final int BEACH_MINUTES = 120;
    /** 느긋한 일정에서 종류별 체류시간에 곱하는 값. 같은 곳에서 30% 더 머문다. */
    static final double RELAXED_STAY_RATE = 1.3;
    /** 알찬 일정에서 종류별 체류시간에 곱하는 값. 같은 곳에서 20% 짧게 머물러 하루에 더 많은 곳을 들른다. */
    static final double PACKED_STAY_RATE = 0.8;
    /** 알찬 일정에서 남는 시간을 채울 때의 하루 관광지 상한. 따로 정하지 않았을 때(4곳)보다 한 곳 많다. */
    public static final int PACKED_DAILY_CAP = TripConstraints.MAX_AUTO_DAILY + 1;
    /** 여행 속도 키워드 이름. 관리자 키워드 관리에서 이름을 바꿀 수 없게 막아 두었다(AdminKeywordService). */
    static final String RELAXED_NAME = "느긋하게", PACKED_NAME = "알차게";

    /** 느긋한 일정인지. 고른 속도가 있으면 그것만 보고, 없으면 여행 목적·처음 입력한 문장의 낱말로 판단한다. */
    public static boolean wanted(ConfirmedTravelConditions conditions) {
        if (conditions == null) return false;
        String chosen = chosen(conditions);
        if (!chosen.isEmpty()) return RELAXED_NAME.equals(chosen);
        return mentions(conditions.travelPurpose()) || mentions(conditions.originalText());
    }

    /** 사용자가 '알차게'를 골랐는지. 문장의 낱말로는 정하지 않는다. */
    public static boolean packed(ConfirmedTravelConditions conditions) {
        return conditions != null && PACKED_NAME.equals(chosen(conditions));
    }

    /** 남는 시간을 관광지로 채울 때 하루에 넣을 수 있는 관광지 수. */
    public static int dailyCap(ConfirmedTravelConditions conditions) {
        return packed(conditions) ? PACKED_DAILY_CAP : TripConstraints.MAX_AUTO_DAILY;
    }

    private static String chosen(ConfirmedTravelConditions conditions) {
        return conditions.selectedKeywords() == null ? "" : conditions.keywordNames("PACE");
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
     * 느긋한 일정이면 1.3배로 늘린다(10분 단위, VisitMinutes.MAX까지).
     * 느긋한 일정에서 바다 취향을 골랐으면 해변은 BEACH_MINUTES분 이상 머문다.
     * 알찬 일정이면 0.8배로 줄인다(10분 단위, VisitMinutes.MIN부터). 탐방로 입구로 가는 산(한라산)은 코스 시간이라 줄이지 않는다.
     */
    public static int visitMinutes(ConfirmedTravelConditions conditions, PlaceCandidate place, int base) {
        int minutes = VisitMinutes.of(place, base);
        if (packed(conditions) && TrailEntrances.of(place).isEmpty())
            return Math.max(VisitMinutes.MIN, (int) Math.round(minutes * PACKED_STAY_RATE / 10.0) * 10);
        if (!wanted(conditions)) return minutes;
        int longer = Math.min(VisitMinutes.MAX, (int) Math.round(minutes * RELAXED_STAY_RATE / 10.0) * 10);
        boolean beach = "해변".equals(TripConstraints.kind(place));
        if (beach && conditions.themeNames().contains("바다")) return Math.max(longer, BEACH_MINUTES);
        return longer;
    }
}
