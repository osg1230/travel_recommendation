package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.*;
import java.util.*;

/**
 * 필수·선호 조건(SFR-02-07)으로 일정에 넣을 관광지를 고르고, 필수 조건끼리 모순되면 임의로 완화하지 않고
 * 충돌 이유와 바꿀 수 있는 선택지를 알린다. 하루 수용량은 식사 60분과 구간당 이동 약 30분을 가정한 추정치이며,
 * 실제로 몇 곳이 들어가는지는 ItineraryGenerator가 카카오 이동시간으로 다시 확인한다.
 */
public final class TripConstraints {
    private TripConstraints() {}

    /** 한 일정에 넣을 수 있는 관광지 수. 장소 목록에 보여 주는 관광지 후보 수(16곳)와 같다. */
    public static final int MAX_TOTAL = 16;
    /** 하루 방문 수로 고를 수 있는 값의 범위. 실제로 들어가는 수는 하루 시간으로 따로 추정한다. */
    public static final int MAX_DAILY = 10;
    public static final Set<String> BUDGET_BASES = Set.of("TOTAL", "PER_PERSON");
    public static final Map<String, String> BUDGET_ITEMS = Map.of("LODGING", "숙박", "MEAL", "식사", "ADMISSION", "입장료", "TRANSPORT", "교통");
    private static final int MEAL_MINUTES = 60, MOVE_MINUTES = 30;
    /** 하루 종료 시간이 이 시각 이후면 저녁 식사도 넣을 수 있는 날로 본다. */
    private static final LocalTime DINNER_DAY_END = LocalTime.of(19, 0);

    public record Conflict(String reason, List<String> choices) {}
    public static class ConflictException extends RuntimeException {
        private final List<Conflict> conflicts;
        public ConflictException(List<Conflict> conflicts) { super("Conflicting required conditions"); this.conflicts = List.copyOf(conflicts); }
        public List<Conflict> conflicts() { return conflicts; }
    }

    /**
     * 좌표가 있는 후보를 후보들 가운데(위도·경도 중앙값)에서 가까운 순서로 앞에 두고, 좌표가 없는 후보는 원래 순서대로 뒤에 둔다.
     * 이름순으로 앞에서부터 고르면 여수 거문도처럼 멀리 떨어진 섬이 우연히 먼저 뽑히므로, 모여 있는 곳부터 고르기 위해 쓴다.
     */
    public static List<PlaceCandidate> nearCenterFirst(List<PlaceCandidate> pool) {
        var located = pool.stream().filter(p -> p.latitude() != null && p.longitude() != null).toList();
        if (located.size() < 2) return pool;
        double lat = median(located.stream().map(PlaceCandidate::latitude).toList());
        double lon = median(located.stream().map(PlaceCandidate::longitude).toList());
        double scale = Math.cos(Math.toRadians(lat));
        var ordered = new ArrayList<>(located);
        ordered.sort(Comparator.comparingDouble(p -> Math.pow((p.longitude() - lon) * scale, 2) + Math.pow(p.latitude() - lat, 2)));
        pool.stream().filter(p -> p.latitude() == null || p.longitude() == null).forEach(ordered::add);
        return List.copyOf(ordered);
    }
    /**
     * 관광지 종류. 카카오 분류(예: "여행 > 관광,명소 > 해수욕장,해변")는 마지막 단계를 쓰고, 해수욕장·해변은 하나로 묶는다.
     * 분류가 없으면 빈 문자열이다.
     */
    public static String kind(PlaceCandidate place) {
        String subtype = place.subtype() == null ? "" : place.subtype().strip();
        String last = subtype.contains(">") ? subtype.substring(subtype.lastIndexOf('>') + 1).strip() : subtype;
        if (last.contains("해수욕장") || last.contains("해변")) return "해변";
        return last;
    }

    /**
     * 같은 종류(예: 해수욕장)가 몰리지 않도록 종류별로 돌아가며 한 곳씩 뽑는다. 종류 안의 순서와 종류가 처음 나온 순서는 그대로 둔다.
     * 예) 해변A·해변B·전망대·해변C·공원 → 해변A·전망대·공원·해변B·해변C
     */
    public static List<PlaceCandidate> mixKinds(List<PlaceCandidate> ordered) {
        Map<String, Deque<PlaceCandidate>> byKind = new LinkedHashMap<>();
        for (var place : ordered) byKind.computeIfAbsent(kind(place), k -> new ArrayDeque<>()).add(place);
        List<PlaceCandidate> mixed = new ArrayList<>();
        while (mixed.size() < ordered.size())
            for (var queue : byKind.values()) if (!queue.isEmpty()) mixed.add(queue.poll());
        return List.copyOf(mixed);
    }

    private static double median(List<Double> values) {
        var sorted = values.stream().sorted().toList();
        int mid = sorted.size() / 2;
        return sorted.size() % 2 == 1 ? sorted.get(mid) : (sorted.get(mid - 1) + sorted.get(mid)) / 2;
    }

    /** 하루 종료 시간이 저녁 식사까지 넣을 만큼 늦은지 확인한다. */
    public static boolean includesDinner(LocalTime end) {
        return !end.isBefore(DINNER_DAY_END);
    }

    /**
     * 하루 시간에서 식사(식당까지 이동 포함)를 빼고 남은 시간에 관광지가 몇 곳 들어가는지 추정한다(최소 1곳).
     * 관광지 한 곳에는 체류시간과 이동 약 30분이 든다고 본다. 예) 09:00~18:00, 체류 90분이면 3곳.
     */
    public static int estimatedDailyCapacity(LocalTime start, LocalTime end, int visitMinutes) {
        int meals = includesDinner(end) ? 2 : 1;
        long minutes = Duration.between(start, end).toMinutes() - meals * (MEAL_MINUTES + MOVE_MINUTES);
        return (int) Math.max(1, minutes / (visitMinutes + MOVE_MINUTES));
    }

    /**
     * 일정에 넣을 관광지를 고른다. 필수 장소를 먼저 넣고 후보 순서대로 채운다.
     * 하루 방문 수를 정하지 않으면 하루 시간으로 추정한 수(여행 일수 × 하루 수용량, 최대 16곳)만큼 고른다.
     * @throws IllegalArgumentException 화면에 없는 장소·범위를 벗어난 값
     * @throws ConflictException 필수 조건끼리 또는 시간·후보 수와 모순될 때
     */
    public static List<PlaceCandidate> select(List<PlaceCandidate> pool, int days, LocalTime start, LocalTime end,
                                              int visitMinutes, Constraints c) {
        if (c == null) return pool.stream().limit(Math.min(MAX_TOTAL, days * estimatedDailyCapacity(start, end, visitMinutes))).toList();
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
        if (c.dailyVisits() == null) target = Math.min(MAX_TOTAL, days * capacity);
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
