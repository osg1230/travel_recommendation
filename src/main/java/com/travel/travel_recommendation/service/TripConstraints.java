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

    /**
     * 하루 방문 수를 정하지 않았을 때 하루에 넣는 관광지 상한. 한국관광공사 당일 추천 코스(700개)의 하루 방문지 중앙값 4곳과
     * 여행 계획 자료(하루 활동 중앙값 4개, 그중 1/3은 식사)를 참고해, 하루 시간이 길어도 4곳을 넘기지 않는다(2026-09-25 조사).
     */
    public static final int MAX_AUTO_DAILY = 4;
    /** 한 일정에 넣을 수 있는 관광지 수. 7일 여행에 하루 3곳씩이다. */
    public static final int MAX_TOTAL = 21;
    /** 장소 목록에 보여 주는 관광지 후보 수의 최소값. 1~3일 여행은 이만큼만 보여 준다. */
    public static final int MIN_CANDIDATES = 16;
    /** 하루 방문 수로 고를 수 있는 값의 범위. 실제로 들어가는 수는 하루 시간으로 따로 추정한다. */
    public static final int MAX_DAILY = 10;
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
     * 중심 장소를 기준으로 관광지 순서를 정한다. 중심 장소를 맨 앞에 두고, 나머지는 가장 가까운 중심 장소와의 거리 순으로
     * 같은 종류를 섞은 뒤 취향에 맞는 곳을 앞으로 옮긴다. 중심 장소가 없으면 지금처럼 전체 가운데에서 가까운 순이다.
     */
    public static List<PlaceCandidate> aroundCenters(List<PlaceCandidate> pool, List<Long> centerIds, List<String> themes) {
        var centers = centerIds.stream().map(id -> pool.stream().filter(p -> p.placeId().equals(id)).findFirst().orElse(null))
            .filter(Objects::nonNull).toList();
        if (centers.isEmpty()) return ThemePlaces.themeFirst(mixKinds(nearCenterFirst(pool)), themes);
        var rest = pool.stream().filter(p -> !centers.contains(p)).toList();
        var located = rest.stream().filter(p -> p.latitude() != null && p.longitude() != null)
            .sorted(Comparator.comparingDouble(p -> nearestCenterDistance(p, centers))).toList();
        var ordered = new ArrayList<>(located);
        rest.stream().filter(p -> p.latitude() == null || p.longitude() == null).forEach(ordered::add);
        var result = new ArrayList<>(centers);
        result.addAll(ThemePlaces.themeFirst(mixKinds(ordered), themes));
        return List.copyOf(result);
    }

    /** 가장 가까운 중심 장소까지의 대략적인 거리(위도 기준 보정한 제곱 거리). 비교에만 쓴다. */
    static double nearestCenterDistance(PlaceCandidate place, List<PlaceCandidate> centers) {
        double best = Double.MAX_VALUE;
        for (var center : centers) {
            if (center.latitude() == null || center.longitude() == null) continue;
            double scale = Math.cos(Math.toRadians(center.latitude()));
            best = Math.min(best, Math.pow((place.longitude() - center.longitude()) * scale, 2) + Math.pow(place.latitude() - center.latitude(), 2));
        }
        return best;
    }

    /**
     * 관광지 종류. 카카오 분류(예: "여행 > 관광,명소 > 해수욕장,해변")는 마지막 단계를 쓰고, 해수욕장·해변은 하나로 묶는다.
     * 분류가 없으면 빈 문자열이다.
     */
    public static String kind(PlaceCandidate place) {
        return kind(place.subtype());
    }

    /** 분류 글자(places.subtype)로 관광지 종류를 정한다. {@link #kind(PlaceCandidate)}와 같은 규칙이다. */
    public static String kind(String subtype) {
        String value = subtype == null ? "" : subtype.strip();
        String last = value.contains(">") ? value.substring(value.lastIndexOf('>') + 1).strip() : value;
        if (last.contains("해수욕장") || last.contains("해변")) return "해변";
        return last;
    }

    /**
     * 직전 초안에 넣었던 관광지(previous)를 순서의 뒤로 보낸다. '다른 관광지로 다시 만들기'에서 이번에는 다른 곳부터 고르게 하기 위해서다.
     * 빼지는 않으므로 후보가 모자라면 다시 쓰인다. 필수·중심·목적 시설·축제 장소(keep)는 제자리에 두고, AI가 뺄 곳으로 적은 곳(skips)은 그보다도 뒤에 둔다.
     */
    public static List<PlaceCandidate> previousLast(List<PlaceCandidate> ordered, Collection<Long> previous,
                                                    Collection<Long> keep, Collection<Long> skips) {
        if (previous.isEmpty()) return ordered;
        java.util.function.Predicate<PlaceCandidate> skipped = p -> skips.contains(p.placeId());
        java.util.function.Predicate<PlaceCandidate> later = p -> previous.contains(p.placeId()) && !keep.contains(p.placeId()) && !skipped.test(p);
        List<PlaceCandidate> result = new ArrayList<>(ordered.stream().filter(later.negate().and(skipped.negate())).toList());
        result.addAll(ordered.stream().filter(later).toList());
        result.addAll(ordered.stream().filter(skipped).toList());
        return result;
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

    /** AI가 정한 순서에서 같은 종류가 이 수를 넘겨 이어지면 다른 종류를 먼저 둔다. */
    static final int MAX_KIND_RUN = 2;

    /**
     * AI가 정한 순서로 관광지를 줄 세운다. 중심 장소를 맨 앞에, AI가 갈 만하다고 적은 순서(picks)를 그다음에,
     * AI가 언급하지 않은 곳을 그 뒤에, AI가 뺄 곳(skips)으로 적은 곳을 맨 뒤에 둔다.
     * 뺄 곳도 목록에는 남겨 두어, 갈 만한 곳이 모자랄 때만 쓰이게 한다. 후보에 없는 ID는 무시한다.
     * 종류를 돌아가며 섞지 않고(mixKinds), 같은 종류가 MAX_KIND_RUN번을 넘겨 이어질 때만 순서를 조정한다.
     */
    public static List<PlaceCandidate> byPicks(List<PlaceCandidate> pool, List<Long> centerIds, List<Long> picks, List<Long> skips) {
        return byPicks(pool, centerIds, picks, skips, Map.of());
    }

    /**
     * activities(AI가 고른 관광지별 주된 활동)가 있으면 이어지는 종류를 분류 이름 대신 활동으로 센다(2026-11-03).
     * 해수욕장·해안길·숲길처럼 분류는 달라도 모두 걷는 곳이 일정 앞쪽에 몰려, 고른 관광지가 거의 다 걷기가 됐기 때문이다.
     */
    public static List<PlaceCandidate> byPicks(List<PlaceCandidate> pool, List<Long> centerIds, List<Long> picks, List<Long> skips,
                                               Map<Long, String> activities) {
        var result = new ArrayList<PlaceCandidate>();
        for (Long id : centerIds) pool.stream().filter(p -> p.placeId().equals(id) && !result.contains(p)).findFirst().ifPresent(result::add);
        var wanted = new ArrayList<PlaceCandidate>();
        for (Long id : picks)
            pool.stream().filter(p -> p.placeId().equals(id) && !result.contains(p) && !wanted.contains(p)).findFirst().ifPresent(wanted::add);
        pool.stream().filter(p -> !result.contains(p) && !wanted.contains(p) && !skips.contains(p.placeId())).forEach(wanted::add);
        result.addAll(limitKindRuns(wanted, p -> activities.getOrDefault(p.placeId(), kind(p))));
        pool.stream().filter(p -> !result.contains(p)).forEach(result::add);
        return List.copyOf(result);
    }

    /**
     * 같은 종류가 MAX_KIND_RUN번 이어졌으면 뒤에서 다른 종류인 곳을 먼저 당겨 온다. 그 밖의 순서는 그대로 둔다.
     * 예) 해변A·해변B·해변C·박물관·해변D → 해변A·해변B·박물관·해변C·해변D
     * 분류가 없거나 '관광,명소'로만 된 곳은 종류를 알 수 없어 이어진 것으로 세지 않는다.
     */
    static List<PlaceCandidate> limitKindRuns(List<PlaceCandidate> ordered) {
        return limitKindRuns(ordered, TripConstraints::kind);
    }

    /** kindOf로 종류를 정해 같은 종류가 MAX_KIND_RUN번을 넘겨 이어지지 않게 한다. */
    static List<PlaceCandidate> limitKindRuns(List<PlaceCandidate> ordered, java.util.function.Function<PlaceCandidate, String> kindOf) {
        var waiting = new ArrayList<>(ordered);
        var result = new ArrayList<PlaceCandidate>();
        while (!waiting.isEmpty()) {
            String run = repeatedKind(result, kindOf);
            var next = waiting.stream().filter(p -> run == null || !run.equals(kindOf.apply(p))).findFirst().orElse(waiting.getFirst());
            waiting.remove(next);
            result.add(next);
        }
        return List.copyOf(result);
    }

    /** 같은 종류의 관광지가 이 거리 안에 있으면 한 곳으로 본다. 예) 대릉원과 그 안의 천마총(약 250m). */
    static final int SAME_SPOT_METERS = 300;

    /**
     * 종류가 같고 아주 가까운 관광지는 앞에 있는 한 곳만 남긴다. 대릉원 안의 천마총처럼 한 장소의 안쪽 시설이 따로 등록되어
     * 같은 곳을 두 번 방문하는 일정을 막기 위해서다. 주소가 달라 주소로는 거를 수 없어 종류와 직선거리로 판단한다.
     * 카카오의 단계별 분류("여행 > 관광,명소 > 문화유적 > 릉,묘,총")가 있는 곳만 보고, 마지막 단계가 '관광,명소'·'문화유적'처럼
     * 넓으면 서로 다른 곳일 수 있어 묶지 않는다. keepIds(필수·중심 장소 등)는 빼지 않는다.
     */
    public static List<PlaceCandidate> withoutSameSpot(List<PlaceCandidate> ordered, Collection<Long> keepIds) {
        var result = new ArrayList<PlaceCandidate>();
        for (var place : ordered) {
            String kind = kind(place);
            boolean specific = place.subtype() != null && place.subtype().contains(">")
                && !kind.isEmpty() && !kind.equals("관광,명소") && !kind.equals("문화유적");
            boolean duplicate = specific && !keepIds.contains(place.placeId()) && result.stream()
                .anyMatch(kept -> kind.equals(kind(kept)) && meters(kept, place) <= SAME_SPOT_METERS);
            if (!duplicate) result.add(place);
        }
        return List.copyOf(result);
    }

    /** 목록의 마지막 MAX_KIND_RUN곳이 모두 같은 종류면 그 종류, 아니면 null. */
    private static String repeatedKind(List<PlaceCandidate> placed, java.util.function.Function<PlaceCandidate, String> kindOf) {
        if (placed.size() < MAX_KIND_RUN) return null;
        String kind = kindOf.apply(placed.getLast());
        if (kind.isEmpty() || kind.equals("관광,명소")) return null;
        for (int i = placed.size() - MAX_KIND_RUN; i < placed.size(); i++) if (!kind.equals(kindOf.apply(placed.get(i)))) return null;
        return kind;
    }

    /** 두 장소 사이의 대략적인 직선거리(m). 좌표가 없으면 아주 먼 것으로 본다. */
    private static double meters(PlaceCandidate a, PlaceCandidate b) {
        if (a.latitude() == null || a.longitude() == null || b.latitude() == null || b.longitude() == null) return Double.MAX_VALUE;
        double east = (a.longitude() - b.longitude()) * Math.cos(Math.toRadians(a.latitude())), north = a.latitude() - b.latitude();
        return Math.sqrt(east * east + north * north) * 111_000;
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

    /** 숙박하는 날 숙소 도착 뒤 저녁을 먹는 기준 시각. */
    public static final LocalTime STAY_DINNER_AT = LocalTime.of(18, 0);

    /**
     * 숙박하는 날에는 하루 종료 시간을 "관광을 마치는 시간"으로 보고, 숙소 도착·저녁 식사·저녁 관광과 숙소 복귀는 이 시각까지 둘 수 있다.
     * 여행 코스 안내에서 저녁 산책·야경 뒤 보통 21~22시에 숙소로 돌아오므로 22시로 둔다(2026-09-25 조사).
     */
    public static LocalTime eveningLimit(LocalTime end) {
        LocalTime evening = LocalTime.of(22, 0);
        return end.isAfter(evening) ? end : evening;
    }

    /** 저녁 관광으로 어울리는 관광지 분류(카카오 분류의 마지막 단계). 밖에서 걷거나 야경을 보는 곳이다. */
    private static final List<String> EVENING_KINDS = List.of("해변", "전망대", "테마거리", "카페거리", "먹자골목", "광장", "케이블카",
        "호수", "저수지", "연못", "하천", "유원지", "공원", "거리");
    /** 이름에 들어 있으면 저녁 관광으로 보는 말. 분류가 '관광,명소'로만 된 곳(예: 여수낭만포차거리)을 찾기 위해 쓴다. */
    private static final List<String> EVENING_WORDS = List.of("야경", "야시장", "포차", "거리", "공원", "해변", "해수욕장", "전망대",
        "스카이워크", "케이블카", "다리", "대교", "광장", "호수", "타워");
    /** 저녁 관광 후보 수. 숙박하는 날마다 한 곳씩 쓰고, 운영시간·거리로 빠질 곳을 대신할 만큼 둔다. */
    static final int MAX_EVENING = 8;

    /**
     * 한국관광공사 야간관광 실태조사(2022)에서 많이 한 활동(야간 산책·식도락·도시야경·야시장)에 맞는 관광지인지 본다.
     * 숲·사찰·박물관처럼 저녁에 닫거나 어두운 곳은 고르지 않는다. 실제 야간 개방·조명 여부는 확인한 것이 아니다.
     */
    public static boolean eveningSpot(PlaceCandidate place) {
        String kind = kind(place);
        if (EVENING_KINDS.stream().anyMatch(kind::contains)) return true;
        String subtype = place.subtype() == null ? "" : place.subtype();
        if (!subtype.isBlank() && !subtype.contains("관광,명소") && !subtype.contains("거리") && !subtype.contains("공원")) return false;
        return EVENING_WORDS.stream().anyMatch(place.name()::contains);
    }

    /**
     * AI가 밤에 둘러보기 좋다고 꼽은 곳(night, 그 순서) 중 저녁 관광으로 어울리는 분류(eveningSpot)인 곳을 limit곳까지(2026-10-31).
     * 필수·중심 장소(excluded)와 AI가 뺄 곳(skips)은 쓰지 않는다. AI의 판단은 저녁 관광 분류 안에서 순서만 바꾸며, 야간 개방·조명은 확인한 것이 아니다.
     */
    public static List<PlaceCandidate> nightPlaces(List<PlaceCandidate> ordered, List<Long> night, Collection<Long> excluded,
                                                   Collection<Long> skips, int limit) {
        return night.stream().map(id -> ordered.stream().filter(p -> p.placeId().equals(id)).findFirst().orElse(null))
            .filter(p -> p != null && !excluded.contains(p.placeId()) && !skips.contains(p.placeId()) && eveningSpot(p))
            .limit(Math.max(0, limit)).toList();
    }

    /** 제외할 곳(낮 관광지·필수·중심 장소)을 뺀 후보 중 저녁 관광으로 어울리는 곳을 limit곳까지. 후보 순서(가운데와 가까운 순)를 따른다. */
    public static List<PlaceCandidate> eveningPlaces(List<PlaceCandidate> ordered, Collection<Long> excluded, int limit) {
        return ordered.stream().filter(p -> !excluded.contains(p.placeId()) && eveningSpot(p)).limit(Math.min(limit, MAX_EVENING)).toList();
    }

    /**
     * 숙박 여행에서 저녁 관광 몫으로 먼저 떼어 둘 수. 숙박하는 날(일수 - 1)마다 한 곳에 거리·운영시간으로 빠질 곳을 대신할 2곳을 더하되,
     * 낮 관광지로 쓸 수(일수 × 하루 수)를 채우고 남는 후보만큼이다. 야경·야시장 같은 곳이 낮 일정에 먼저 쓰이지 않게 한다.
     */
    public static int eveningShare(int candidates, int days, int daily) {
        int daytime = Math.min(MAX_TOTAL, days * daily);
        return Math.max(0, Math.min(days + 1, candidates - daytime));
    }

    /**
     * 하루 시간에서 식사(식당까지 이동 포함)를 빼고 남은 시간에 관광지가 몇 곳 들어가는지 추정한다(최소 1곳, 최대 MAX_AUTO_DAILY곳).
     * 관광지 한 곳에는 체류시간과 이동 약 30분이 든다고 본다. 예) 09:00~18:00, 체류 90분이면 3곳.
     */
    /**
     * 낮 관광지를 몇 곳 고를지 정하는 하루 수용량(2026-11-16). 기본 체류시간 대신 후보 앞쪽(일수 × MAX_AUTO_DAILY곳)의
     * 종류별 체류시간(VisitMinutes) 평균으로 계산한다. 해변·등대·시장처럼 짧은 곳이 많은데 90분 기준으로 하루 3곳만 골라
     * 오후가 남았고, 그 시간을 예비 관광지로 채우다 보니 1일차가 예비를 다 써서 뒤쪽 날이 일찍 끝났다(속초 2박 3일).
     * 평균이 기본 체류시간보다 길면 지금처럼 기본 체류시간을 쓴다(고르는 수가 줄지는 않는다).
     */
    public static int plannedDailyCapacity(List<PlaceCandidate> pool, int days, LocalTime start, LocalTime end, int visitMinutes) {
        var front = pool.stream().limit((long) Math.max(1, days) * MAX_AUTO_DAILY).toList();
        if (front.isEmpty()) return estimatedDailyCapacity(start, end, visitMinutes);
        int average = (int) Math.round(front.stream().mapToInt(p -> VisitMinutes.of(p, visitMinutes)).average().orElse(visitMinutes));
        return estimatedDailyCapacity(start, end, Math.min(visitMinutes, average));
    }

    public static int estimatedDailyCapacity(LocalTime start, LocalTime end, int visitMinutes) {
        int meals = includesDinner(end) ? 2 : 1;
        long minutes = Duration.between(start, end).toMinutes() - meals * (MEAL_MINUTES + MOVE_MINUTES);
        return (int) Math.min(MAX_AUTO_DAILY, Math.max(1, minutes / (visitMinutes + MOVE_MINUTES)));
    }

    /**
     * 여행 일수에 맞춘 관광지 후보 수. 하루 최대 4곳씩 채울 만큼(전체 최대 21곳)에 섬처럼 갈 수 없는 곳을 대신할 예비 6곳을 더한다.
     * 예) 1~2일 16곳, 4일 22곳, 5일 이상 26~27곳. 16곳으로 고정하면 4박 5일부터 넣을 관광지가 모자랐다.
     */
    public static int candidateLimit(Integer days) {
        int d = days == null ? 1 : days;
        return Math.max(MIN_CANDIDATES, Math.min(MAX_TOTAL, d * MAX_AUTO_DAILY) + ItineraryJobService.MAX_RESERVE);
    }

    /**
     * 일정에 넣을 관광지를 고른다. 필수 장소를 먼저 넣고 후보 순서대로 채운다.
     * 하루 방문 수를 정하지 않으면 하루 시간으로 추정한 수(여행 일수 × 하루 수용량, 최대 21곳)만큼 고른다.
     * @throws IllegalArgumentException 화면에 없는 장소·범위를 벗어난 값
     * @throws ConflictException 필수 조건끼리 또는 시간·후보 수와 모순될 때
     */
    public static List<PlaceCandidate> select(List<PlaceCandidate> pool, int days, LocalTime start, LocalTime end,
                                              int visitMinutes, Constraints c) {
        // 몇 곳을 고를지는 후보의 종류별 체류시간으로 본 하루 수용량(planned)으로 정한다. 필수 장소 검사는 아래에서 기본 체류시간(capacity)으로 한다.
        int planned = plannedDailyCapacity(pool, days, start, end, visitMinutes);
        if (c == null) return pool.stream().limit(Math.min(MAX_TOTAL, days * planned)).toList();
        var poolIds = pool.stream().map(PlaceCandidate::placeId).toList();
        if (!poolIds.containsAll(c.requiredPlaceIds()) || new HashSet<>(c.requiredPlaceIds()).size() != c.requiredPlaceIds().size())
            throw new IllegalArgumentException("Unknown required place");
        if (!poolIds.containsAll(c.centerPlaceIds()) || new HashSet<>(c.centerPlaceIds()).size() != c.centerPlaceIds().size())
            throw new IllegalArgumentException("Unknown center place");
        if (c.dailyVisits() != null && (c.dailyVisits() < 1 || c.dailyVisits() > MAX_DAILY))
            throw new IllegalArgumentException("Daily visits out of range");
        if (c.dailyVisitsRequired() && c.dailyVisits() == null) throw new IllegalArgumentException("Required daily visits missing");
        // 숙소 예산대는 정해진 세 값만 받는다(SFR-03-03).
        if (c.stayTier() != null && java.util.Arrays.stream(BudgetGuide.StayTier.values()).noneMatch(t -> t.name().equals(c.stayTier())))
            throw new IllegalArgumentException("Unknown stay tier");

        int required = c.requiredPlaceIds().size(), capacity = estimatedDailyCapacity(start, end, visitMinutes);
        var conflicts = new ArrayList<Conflict>();
        if (c.centerPlaceIds().size() > days)
            conflicts.add(new Conflict("일정의 중심으로 삼을 곳은 여행 일수(" + days + "일)만큼 고를 수 있어요. 지금 " + c.centerPlaceIds().size() + "곳이에요.",
                List.of("중심으로 삼을 곳을 " + days + "곳 이하로 줄이기", "여행 조건에서 기간 늘리기")));
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
        if (c.dailyVisits() == null) target = Math.min(MAX_TOTAL, days * planned);
        else if (c.dailyVisitsRequired()) target = days * c.dailyVisits();
        // 선호 조건은 시간·후보가 허락하는 만큼만 반영한다.
        else target = Math.min(MAX_TOTAL, days * Math.min(c.dailyVisits(), planned));
        target = Math.min(Math.max(target, required), pool.size());
        var selected = new ArrayList<PlaceCandidate>();
        pool.stream().filter(p -> c.requiredPlaceIds().contains(p.placeId())).forEach(selected::add);
        pool.stream().filter(p -> !c.requiredPlaceIds().contains(p.placeId())).limit(Math.max(0, target - selected.size())).forEach(selected::add);
        return List.copyOf(selected);
    }
}
