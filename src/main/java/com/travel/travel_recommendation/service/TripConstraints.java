package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.*;
import java.util.*;

/**
 * 필수·선호 조건(SFR-02-07)으로 일정에 넣을 관광지를 고르고, 필수 조건끼리 모순되면 임의로 완화하지 않고
 * 충돌 이유와 바꿀 수 있는 선택지를 알린다. 하루 수용량은 식사 60분과 구간당 이동 약 30분을 가정한 추정치다.
 */
public final class TripConstraints {
    private TripConstraints() {}

    public static final int MAX_TOTAL = 8;
    public static final int MAX_DAILY = 4;
    public static final Set<String> BUDGET_BASES = Set.of("TOTAL", "PER_PERSON");
    public static final Map<String, String> BUDGET_ITEMS = Map.of("LODGING", "숙박", "MEAL", "식사", "ADMISSION", "입장료", "TRANSPORT", "교통");
    private static final int MEAL_MINUTES = 60, MOVE_MINUTES = 30;

    public record Conflict(String reason, List<String> choices) {}
    public static class ConflictException extends RuntimeException {
        private final List<Conflict> conflicts;
        public ConflictException(List<Conflict> conflicts) { super("Conflicting required conditions"); this.conflicts = List.copyOf(conflicts); }
        public List<Conflict> conflicts() { return conflicts; }
    }

    /** 식사와 이동을 넣었을 때 하루에 들어갈 수 있는 관광지 수의 추정치(최소 1곳). */
    public static int estimatedDailyCapacity(LocalTime start, LocalTime end, int visitMinutes) {
        long minutes = Duration.between(start, end).toMinutes() - MEAL_MINUTES;
        return (int) Math.max(1, Math.min(MAX_DAILY, minutes / (visitMinutes + MOVE_MINUTES)));
    }

    /**
     * 일정에 넣을 관광지를 고른다. 필수 장소를 먼저 넣고 후보 순서대로 채운다.
     * 조건이 없으면 기존 규칙(여행 일수 × 2곳, 최대 8곳)과 같다.
     * @throws IllegalArgumentException 화면에 없는 장소·범위를 벗어난 값
     * @throws ConflictException 필수 조건끼리 또는 시간·후보 수와 모순될 때
     */
    public static List<PlaceCandidate> select(List<PlaceCandidate> pool, int days, LocalTime start, LocalTime end,
                                              int visitMinutes, Constraints c) {
        if (c == null) return pool.stream().limit(Math.min(MAX_TOTAL, days * 2)).toList();
        var poolIds = pool.stream().map(PlaceCandidate::placeId).toList();
        if (!poolIds.containsAll(c.requiredPlaceIds()) || new HashSet<>(c.requiredPlaceIds()).size() != c.requiredPlaceIds().size())
            throw new IllegalArgumentException("Unknown required place");
        if (c.dailyVisits() != null && (c.dailyVisits() < 1 || c.dailyVisits() > MAX_DAILY))
            throw new IllegalArgumentException("Daily visits out of range");
        if (c.dailyVisitsRequired() && c.dailyVisits() == null) throw new IllegalArgumentException("Required daily visits missing");
        if (c.budgetBasis() != null && !BUDGET_BASES.contains(c.budgetBasis())) throw new IllegalArgumentException("Unknown budget basis");
        if (!BUDGET_ITEMS.keySet().containsAll(c.budgetIncludes())) throw new IllegalArgumentException("Unknown budget item");
        // 숙소 예산대는 정해진 세 값만 받는다(SFR-03-03).
        if (c.stayTier() != null && java.util.Arrays.stream(BudgetGuide.StayTier.values()).noneMatch(t -> t.name().equals(c.stayTier())))
            throw new IllegalArgumentException("Unknown stay tier");

        int required = c.requiredPlaceIds().size(), capacity = estimatedDailyCapacity(start, end, visitMinutes);
        var conflicts = new ArrayList<Conflict>();
        if (required > MAX_TOTAL)
            conflicts.add(new Conflict("필수 방문 장소는 한 일정에 최대 " + MAX_TOTAL + "곳까지 넣을 수 있어요. 지금 " + required + "곳이에요.",
                List.of("필수 방문 장소를 " + MAX_TOTAL + "곳 이하로 줄이기", "일부를 필수에서 빼고 다른 일정으로 나누기")));
        if (required > days * capacity)
            conflicts.add(new Conflict("필수 방문 장소 " + required + "곳을 " + days + "일 동안 모두 넣기 어려워요. 지금 시간(" + start + "~" + end
                + ", 체류 " + visitMinutes + "분)이면 하루 약 " + capacity + "곳까지로 추정돼요.",
                List.of("하루 시간을 늘리기", "관광지 체류시간 줄이기", "필수 방문 장소 줄이기", "여행 조건에서 기간 늘리기")));
        if (c.dailyVisitsRequired()) {
            int daily = c.dailyVisits();
            if (required > days * daily)
                conflicts.add(new Conflict("필수 방문 장소 " + required + "곳을 하루 " + daily + "곳씩 " + days + "일에 모두 넣을 수 없어요.",
                    List.of("하루 방문 수를 늘리기", "하루 방문 수를 '가능하면 반영'으로 바꾸기", "필수 방문 장소 줄이기")));
            if (daily > capacity)
                conflicts.add(new Conflict("하루 " + start + "~" + end + "에 관광지 " + daily + "곳(체류 " + visitMinutes
                    + "분)은 식사·이동시간을 넣으면 들어가기 어려워요. 하루 약 " + capacity + "곳까지로 추정돼요.",
                    List.of("하루 방문 수 줄이기", "하루 방문 수를 '가능하면 반영'으로 바꾸기", "하루 시간 늘리기", "관광지 체류시간 줄이기")));
            if (days * daily > MAX_TOTAL)
                conflicts.add(new Conflict("한 일정에 넣을 수 있는 관광지는 전체 최대 " + MAX_TOTAL + "곳이라 하루 " + daily + "곳씩 " + days + "일은 만들 수 없어요.",
                    List.of("하루 방문 수 줄이기", "하루 방문 수를 '가능하면 반영'으로 바꾸기")));
            else if (days * daily > pool.size())
                conflicts.add(new Conflict("확인된 관광지 후보가 " + pool.size() + "곳이라 하루 " + daily + "곳씩 " + days + "일을 채울 수 없어요.",
                    List.of("장소 목록에서 '카카오에서 장소 더 찾아보기'로 후보 늘리기", "하루 방문 수 줄이기", "하루 방문 수를 '가능하면 반영'으로 바꾸기")));
        }
        if (!conflicts.isEmpty()) throw new ConflictException(conflicts);

        int target;
        if (c.dailyVisits() == null) target = Math.min(MAX_TOTAL, days * 2);
        else if (c.dailyVisitsRequired()) target = days * c.dailyVisits();
        // 선호 조건은 시간·후보가 허락하는 만큼만 반영한다.
        else target = Math.min(MAX_TOTAL, days * Math.min(c.dailyVisits(), capacity));
        target = Math.min(Math.max(target, required), pool.size());
        var selected = new ArrayList<PlaceCandidate>();
        pool.stream().filter(p -> c.requiredPlaceIds().contains(p.placeId())).forEach(selected::add);
        pool.stream().filter(p -> !c.requiredPlaceIds().contains(p.placeId())).limit(Math.max(0, target - selected.size())).forEach(selected::add);
        return List.copyOf(selected);
    }
}
