package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.BudgetGuide.StayTier;
import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.util.*;

/**
 * 일정 초안의 항목별 예상 경비. 왕복 교통·숙박·식비·관광·시내 이동·카페·기타를 전체 인원 합계 범위(최소~최대)로 계산한다.
 * 참고 가격을 찾은 숙소·음식점은 그 값을 쓰고, 나머지는 아래에 둔 기준 범위로 추정한다.
 * 실제 요금이 아니므로 예산 충족을 판단하지 않고, 입력한 예산이 있으면 범위와 비교한 안내(budgetNote)만 덧붙인다.
 */
public record TripCostEstimate(List<Line> lines, List<String> excluded, int partySize, String partyBasis, int days, int nights,
                               String budgetNote) {

    /** 항목 하나. min·max는 전체 인원 합계(원), basis는 계산 근거다. */
    public record Line(String name, int min, int max, String basis) {}

    // 기준 범위(원). 국내 여행의 대략적인 값이라 실제 요금과 다를 수 있다. 바꾸면 화면 근거 문구에도 그대로 반영된다.
    static final int MEAL_MIN = 9_000, MEAL_MAX = 15_000;                       // 1끼 1인
    static final int ADMISSION_MIN = 0, ADMISSION_MAX = 6_000;                  // 관광지 1곳 1인(무료 관광지가 많아 최소 0원, 유료는 대개 수천 원)
    static final int CAFE_MIN = 5_000, CAFE_MAX = 15_000;                       // 하루 1인 카페·간식·기타
    static final int CAR_LOCAL_MIN = 5_000, CAR_LOCAL_MAX = 15_000;             // 자동차 하루 주차·시내 연료(차량 1대)
    static final int TRANSIT_LOCAL_MIN = 3_000, TRANSIT_LOCAL_MAX = 10_000;     // 대중교통 하루 1인 시내 이동
    static final int CAR_WON_PER_KM_MIN = 120, CAR_WON_PER_KM_MAX = 200;        // 자동차 1km 연료+통행료
    static final int CAR_KM_PER_HOUR = 70;                                      // 편도 시간을 거리로 바꿀 때의 평균 속도
    static final int TRANSIT_WON_PER_MINUTE_MIN = 120, TRANSIT_WON_PER_MINUTE_MAX = 350; // 시외버스~KTX 요금을 1분당 1인으로 환산
    static final int FLIGHT_MIN = 40_000, FLIGHT_MAX = 120_000;                 // 국내선 편도 1인
    static final int PEOPLE_PER_ROOM = 4;                                       // 객실 1개로 계산하는 최대 인원
    /** 숙소 유형별 1박 객실 요금 범위. 유형을 알 수 없으면 UNKNOWN_STAY를 쓴다. */
    static final Map<StayTier, int[]> STAY_PER_NIGHT = Map.of(
        StayTier.BUDGET, new int[]{40_000, 80_000},
        StayTier.STANDARD, new int[]{80_000, 160_000},
        StayTier.COMFORT, new int[]{150_000, 300_000});
    static final int[] UNKNOWN_STAY = {60_000, 160_000};

    public static TripCostEstimate of(Input input, Result result) {
        var conditions = input.conditions();
        // 인원은 입력한 값을 먼저 쓰고, 없으면 동행(연인 2명·혼자 1명)으로 추정한다. 그 밖에는 1인 기준으로 계산한다.
        int party; String partyBasis;
        if (conditions.partySize() != null && conditions.partySize() > 0) { party = conditions.partySize(); partyBasis = "입력한 인원"; }
        else if (companion(conditions, "COUPLE")) { party = 2; partyBasis = "동행 '연인'으로 추정한 인원"; }
        else if (companion(conditions, "SOLO")) { party = 1; partyBasis = "동행 '혼자'로 추정한 인원"; }
        else { party = 1; partyBasis = "인원을 정하지 않아 1인 기준"; }

        int days = Math.max(1, result.days().size());
        boolean staying = result.lodging() != null && input.places().accommodationNeeded() && days > 1;
        int nights = staying ? days - 1 : 0;
        Map<Long, PriceQuote> found = new HashMap<>();
        if (result.prices() != null) for (var quote : result.prices().quotes()) if (quote.found()) found.put(quote.placeId(), quote);

        var lines = new ArrayList<Line>();
        var excluded = new ArrayList<String>();

        // 1. 집과 여행지를 오가는 교통비. 집 출발·귀가를 정했을 때만 편도 이동시간으로 추정한다.
        if (input.home() == null) excluded.add("집 출발·귀가를 정하지 않아 집과 여행지를 오가는 교통비는 넣지 않았어요.");
        else lines.add(intercity(input.home(), result.transport(), party));

        // 2. 숙박. 찾은 참고 가격이 있으면 그 값, 없으면 숙소 유형(절약형·보통·여유형)별 기준 범위를 쓴다.
        if (nights > 0) {
            int rooms = (party + PEOPLE_PER_ROOM - 1) / PEOPLE_PER_ROOM;
            String roomText = "객실 " + rooms + "개(" + PEOPLE_PER_ROOM + "명까지 1개로 계산)";
            var quote = found.get(result.lodging().chosenPlaceId());
            if (quote != null) {
                lines.add(new Line("숙박", quote.minWon() * nights * rooms, quote.maxWon() * nights * rooms,
                    "찾은 참고 가격 1박 " + won(quote.minWon()) + "~" + won(quote.maxWon()) + "원 × " + nights + "박 × " + roomText));
            } else {
                var tier = result.lodging().options().stream()
                    .filter(option -> Objects.equals(option.placeId(), result.lodging().chosenPlaceId())).findFirst()
                    .flatMap(option -> StayTier.of(option.subtype(), option.name()));
                int[] range = tier.map(STAY_PER_NIGHT::get).orElse(UNKNOWN_STAY);
                lines.add(new Line("숙박", range[0] * nights * rooms, range[1] * nights * rooms,
                    tier.map(t -> "숙소 유형 '" + t.getLabel() + "' 기준").orElse("숙소 유형을 알 수 없어 일반 기준")
                        + " 1박 " + won(range[0]) + "~" + won(range[1]) + "원 × " + nights + "박 × " + roomText));
            }
        } else if (input.places().accommodationNeeded() && days > 1) {
            excluded.add("일정에 숙소가 없어 숙박비는 넣지 않았어요.");
        }

        // 3. 식비. 참고 가격을 찾은 끼니는 그 값(1인분 × 인원), 나머지는 기준 범위로 더한다.
        int meals = 0, priced = 0, mealMin = 0, mealMax = 0, visits = 0;
        for (var day : result.days()) for (var item : day.items()) {
            if ("VISIT".equals(item.type())) visits++;
            if (!"MEAL".equals(item.type())) continue;
            meals++;
            var quote = found.get(item.placeId());
            if (quote != null) { priced++; mealMin += quote.minWon() * party; mealMax += quote.maxWon() * party; }
            else { mealMin += MEAL_MIN * party; mealMax += MEAL_MAX * party; }
        }
        if (meals > 0) {
            String basis = meals + "끼 × " + party + "명";
            if (priced > 0) basis += ", 참고 가격을 찾은 " + priced + "끼는 그 값";
            if (priced < meals) basis += ", " + (priced > 0 ? "나머지는 " : "") + "1끼 1인 " + won(MEAL_MIN) + "~" + won(MEAL_MAX) + "원 기준";
            lines.add(new Line("식비", mealMin, mealMax, basis));
        }

        // 4. 관광·입장료. 입장료 자료가 없어 관광지 수로만 범위를 잡는다.
        if (visits > 0) lines.add(new Line("관광·입장료", visits * party * ADMISSION_MIN, visits * party * ADMISSION_MAX,
            "관광지 " + visits + "곳 × " + party + "명 × 1곳 " + won(ADMISSION_MIN) + "~" + won(ADMISSION_MAX) + "원(무료 관광지가 많아 최소 0원)"));

        // 5. 여행지 안 이동. 자동차는 차량 1대의 주차·연료, 대중교통은 1인 버스·지하철·택시 기준이다.
        if (result.transport() == Transport.CAR) {
            lines.add(new Line("시내 이동(주차·연료)", days * CAR_LOCAL_MIN, days * CAR_LOCAL_MAX,
                days + "일 × 하루 " + won(CAR_LOCAL_MIN) + "~" + won(CAR_LOCAL_MAX) + "원, 차량 1대"));
        } else {
            lines.add(new Line("시내 이동(버스·지하철·택시)", days * party * TRANSIT_LOCAL_MIN, days * party * TRANSIT_LOCAL_MAX,
                days + "일 × " + party + "명 × 하루 " + won(TRANSIT_LOCAL_MIN) + "~" + won(TRANSIT_LOCAL_MAX) + "원"));
        }

        // 6. 카페·간식·기타.
        lines.add(new Line("카페·간식·기타", days * party * CAFE_MIN, days * party * CAFE_MAX,
            days + "일 × " + party + "명 × 하루 " + won(CAFE_MIN) + "~" + won(CAFE_MAX) + "원"));

        var estimate = new TripCostEstimate(List.copyOf(lines), List.copyOf(excluded), party, partyBasis, days, nights, null);
        return estimate.withBudgetNote(input);
    }

    /** 집↔여행지 왕복 교통비. 자동차는 편도 시간을 거리로 바꿔 연료·통행료를, 대중교통은 1분당 요금(비행기는 편도 고정 범위)을 쓴다. */
    private static Line intercity(HomeTrip home, Transport transport, int party) {
        int out = home.minutes(), back = home.returnMinutes();
        String legs = "편도 약 " + ReadableText.duration(out) + (back == out ? "" : "·" + ReadableText.duration(back));
        if (transport == Transport.CAR) {
            int km = (out + back) * CAR_KM_PER_HOUR / 60;
            return new Line("왕복 교통(자동차)", round(km * CAR_WON_PER_KM_MIN), round(km * CAR_WON_PER_KM_MAX),
                legs + " → 왕복 약 " + km + "km × 연료·통행료 1km " + CAR_WON_PER_KM_MIN + "~" + CAR_WON_PER_KM_MAX + "원, 차량 1대");
        }
        int min = legCost(out, home.basis(), true) + legCost(back, home.returnBasis(), true);
        int max = legCost(out, home.basis(), false) + legCost(back, home.returnBasis(), false);
        boolean flight = isFlight(home.basis()) || isFlight(home.returnBasis());
        String basis = flight
            ? "비행기 편도 1인 " + won(FLIGHT_MIN) + "~" + won(FLIGHT_MAX) + "원 기준 왕복 × " + party + "명"
            : legs + " 기준 1인 왕복 " + won(round(min)) + "~" + won(round(max)) + "원(시외버스~KTX 요금을 1분당 "
                + TRANSIT_WON_PER_MINUTE_MIN + "~" + TRANSIT_WON_PER_MINUTE_MAX + "원으로 환산) × " + party + "명";
        return new Line("왕복 교통(대중교통)", round(min * party), round(max * party), basis);
    }

    private static int legCost(int minutes, String basis, boolean minimum) {
        if (isFlight(basis)) return minimum ? FLIGHT_MIN : FLIGHT_MAX;
        return minutes * (minimum ? TRANSIT_WON_PER_MINUTE_MIN : TRANSIT_WON_PER_MINUTE_MAX);
    }

    // 집 이동시간의 근거 문구가 "비행기 · 김포 → 제주 ..."처럼 비행기로 찾은 경우다(HomeTripService·FlightGuideService).
    private static boolean isFlight(String basis) { return basis != null && basis.contains("비행기"); }

    // 동행 키워드는 이름이 바뀌어도 값(valueJson의 value)으로 구분한다. 예) {"value":"COUPLE"}
    private static boolean companion(ConfirmedTravelConditions conditions, String value) {
        return conditions.selectedKeywords() != null && conditions.selectedKeywords().stream()
            .anyMatch(k -> "COMPANION".equals(k.categoryCode()) && k.valueJson() != null
                && k.valueJson().replace(" ", "").contains("\"value\":\"" + value + "\""));
    }

    /** 입력한 예산(전체 인원의 총예산)을 추정 범위와 비교한 한 줄 안내를 붙인다. 예산이 없으면 안내 없이 그대로다. */
    private TripCostEstimate withBudgetNote(Input input) {
        var budget = input.conditions().budget();
        if (budget == null) return this;
        long total = budget.longValue();
        String entered = "입력한 예산 " + won(total) + "원";
        String note = total < totalMin() ? entered + "은 추정 최소보다 적어요. 숙소 유형이나 식사 횟수를 조정해 보세요."
            : total > totalMax() ? entered + "은 추정 최대보다 많아요."
            // 범위 안이어도 권장 계획 예산보다 적으면 빠듯할 수 있다는 점을 함께 알린다.
            : total < planningWon() ? entered + "은 추정 범위 안이지만 권장 계획 예산(" + won(planningWon()) + "원)보다 적어 빠듯할 수 있어요."
            : entered + "은 추정 범위 안이에요.";
        return new TripCostEstimate(lines, excluded, partySize, partyBasis, days, nights, note);
    }

    public int totalMin() { return lines.stream().mapToInt(Line::min).sum(); }
    public int totalMax() { return lines.stream().mapToInt(Line::max).sum(); }
    public int perPersonMin() { return round(totalMin() / partySize); }
    public int perPersonMax() { return round(totalMax() / partySize); }
    /** 권장 계획 예산: 추정 범위의 중간값을 만 원 단위로 올린 전체 합계다. */
    public int planningWon() {
        int middle = (totalMin() + totalMax()) / 2;
        return (middle + 9_999) / 10_000 * 10_000;
    }

    /** 천 원 단위로 반올림한다. 기준 범위에서 나온 어림수를 깔끔하게 보여 주기 위한 것이다. */
    private static int round(int won) { return (won + 500) / 1000 * 1000; }
    private static String won(long amount) { return String.format("%,d", amount); }
}
