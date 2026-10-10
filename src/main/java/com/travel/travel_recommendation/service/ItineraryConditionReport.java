package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;

/**
 * 확정한 여행 조건과 만들어진 일정을 서버가 비교한 결과(SFR-04-06).
 * AI 안내와 관계없이 서버 계산만 사용하고, 정보가 부족하면 충족이 아니라 확인 필요로 둔다.
 */
public final class ItineraryConditionReport {
    private ItineraryConditionReport() {}

    public enum Status {
        MET("충족"), UNMET("미충족"), NEEDS_CHECK("확인 필요"), NOT_APPLICABLE("해당 없음");
        private final String label;
        Status(String label) { this.label = label; }
        public String getLabel() { return label; }
    }
    public record Check(String name, Status status, String detail) {}
    /** complete=false이면 미충족 항목이 남아 있어 완료 일정으로 저장하지 않는다. */
    public record Report(List<Check> checks, boolean complete) {
        public long count(Status status) { return checks.stream().filter(c -> c.status() == status).count(); }
    }

    public static Report evaluate(Input input, Result result) {
        var checks = new ArrayList<>(List.of(period(input, result), required(input, result), attractions(input, result), duplicates(result),
            dailyVisits(input, result), themes(input, result), timeline(input, result), travelTimes(input, result), operating(input, result),
            accommodation(input, result), budget(input)));
        if (input.revision() != null) checks.add(2, kept(input, result));
        return new Report(List.copyOf(checks), checks.stream().noneMatch(c -> c.status() == Status.UNMET));
    }

    private static Check period(Input input, Result result) {
        var c = input.conditions(); int days = result.days().size();
        if (c.durationDays() == null || days != c.durationDays())
            return new Check("여행 기간", Status.UNMET, "확정한 " + c.durationDays() + "일과 일정 " + days + "일이 달라요.");
        if (c.startDate() == null)
            return new Check("여행 기간", Status.NEEDS_CHECK, days + "일 일정이에요. 출발일이 정해지지 않아 날짜별 운영 여부는 판단하지 않았어요.");
        for (int i = 0; i < days; i++)
            if (!c.startDate().plusDays(i).equals(result.days().get(i).date()))
                return new Check("여행 기간", Status.UNMET, (i + 1) + "일차 날짜가 확정한 여행 기간과 달라요.");
        return new Check("여행 기간", Status.MET, c.startDate() + " ~ " + result.days().getLast().date() + " · " + days + "일");
    }

    // 수정안은 사용자가 유지하기로 한 관광지를 빠짐없이 그대로 포함해야 한다(SFR-04-07).
    private static Check kept(Input input, Result result) {
        var visited = visits(result).stream().map(Item::placeId).toList();
        var missing = input.revision().kept().stream().filter(p -> !visited.contains(p.placeId())).map(PlaceRef::name).toList();
        // 관광지를 더하기만 한 수정안은 더한 곳이 먼저라, 시간이 모자라면 기존 관광지가 빠질 수 있다. 저장은 막지 않고 알린다.
        if (!missing.isEmpty() && ItineraryGenerator.addOnly(input.revision()))
            return new Check("유지한 관광지", Status.NEEDS_CHECK, "관광지를 더하느라 하루 시간이 모자라 빠진 기존 관광지: " + String.join(", ", missing));
        if (!missing.isEmpty()) return new Check("유지한 관광지", Status.UNMET, "빠진 관광지: " + String.join(", ", missing));
        return new Check("유지한 관광지", Status.MET, "유지하기로 한 " + input.revision().kept().size()
            + "곳을 모두 포함했어요. 방문 시각은 동선에 맞춰 다시 계산했어요.");
    }

    // 일정에 쓰기로 고른 관광지(필수 장소 포함)가 들어갔는지 확인한다. 시간이 부족하거나 너무 멀어 뺀 곳(skipped)은 이유와 함께 알린다.
    private static Check attractions(Input input, Result result) {
        var visited = visits(result).stream().map(Item::placeId).toList();
        var skipped = result.skipped().stream().map(PlaceRef::placeId).toList();
        var missing = input.places().attractions().stream()
            .filter(p -> !visited.contains(p.placeId()) && !skipped.contains(p.placeId())).map(p -> p.name()).toList();
        if (!missing.isEmpty()) return new Check("선택한 관광지 포함", Status.UNMET, "빠진 관광지: " + String.join(", ", missing));
        if (!skipped.isEmpty()) return new Check("선택한 관광지 포함", Status.MET, "하루 시간에 맞춰 관광지 " + (input.places().attractions().size() - skipped.size())
            + "곳을 배치했어요. 하루 시간이 부족하거나 가는 길이 2시간을 넘어(섬 등) " + String.join(", ", result.skipped().stream().map(PlaceRef::name).toList()) + "은(는) 넣지 않았어요.");
        return new Check("선택한 관광지 포함", Status.MET, "일정에 쓰기로 고른 관광지 " + input.places().attractions().size() + "곳을 모두 배치했어요.");
    }

    // 사용자가 필수로 지정한 방문 장소가 모두 들어갔는지 확인한다(SFR-02-07).
    private static Check required(Input input, Result result) {
        var c = input.constraints();
        if (c == null || c.requiredPlaceIds().isEmpty()) return new Check("필수 방문 장소", Status.NOT_APPLICABLE, "필수로 지정한 장소가 없어요.");
        var visited = visits(result).stream().map(Item::placeId).toList();
        var names = new LinkedHashMap<Long, String>();
        input.places().attractions().forEach(p -> names.put(p.placeId(), p.name()));
        var missing = c.requiredPlaceIds().stream().filter(id -> !visited.contains(id)).map(id -> names.getOrDefault(id, "장소 " + id)).toList();
        if (!missing.isEmpty()) return new Check("필수 방문 장소", Status.UNMET, "빠진 필수 장소: " + String.join(", ", missing));
        return new Check("필수 방문 장소", Status.MET, "필수로 지정한 " + c.requiredPlaceIds().size() + "곳("
            + String.join(", ", c.requiredPlaceIds().stream().map(id -> names.getOrDefault(id, "장소 " + id)).toList()) + ")을 모두 넣었어요.");
    }

    private static Check duplicates(Result result) {
        var seen = new HashSet<Long>(); var repeated = new LinkedHashSet<String>();
        for (var item : visits(result)) if (!seen.add(item.placeId())) repeated.add(item.name());
        if (!repeated.isEmpty()) return new Check("방문 중복", Status.UNMET, "두 번 이상 배치된 관광지: " + String.join(", ", repeated));
        return new Check("방문 중복", Status.MET, "같은 관광지를 두 번 배치하지 않았어요.");
    }

    // 하루 방문 수를 정하지 않았으면 비어 있는 날만 미충족으로 본다. 정했으면 필수는 미충족, 선호는 확인 필요로 구분한다.
    // 숙소 도착 뒤의 저녁 관광도 하루 방문 수에 센다(일정 생성·검증과 같은 기준, 예: 하루 5곳 = 낮 4곳 + 저녁 1곳).
    private static Check dailyVisits(Input input, Result result) {
        var counts = new ArrayList<String>();
        var totals = new ArrayList<Long>();
        for (var day : result.days()) {
            long visits = day.items().stream().filter(i -> "VISIT".equals(i.type())).count();
            if (visits == 0) return new Check("하루 방문 수", Status.UNMET, day.number() + "일차에 관광지가 없어요.");
            long evening = eveningVisits(day);
            counts.add(day.number() + "일차 " + visits + "곳" + (evening > 0 ? "(저녁 관광 " + evening + "곳 포함)" : ""));
            totals.add(visits);
        }
        var c = input.constraints();
        if (c == null || c.dailyVisits() == null)
            return new Check("하루 방문 수", Status.MET, String.join(" · ", counts) + " (따로 정하지 않아 이동시간에 맞춰 나눴어요)");
        int want = c.dailyVisits();
        boolean every = totals.stream().allMatch(n -> n == want);
        if (every) return new Check("하루 방문 수", Status.MET, "하루 " + want + "곳 (" + (c.dailyVisitsRequired() ? "필수" : "선호") + ") · " + String.join(" · ", counts));
        if (c.dailyVisitsRequired()) return new Check("하루 방문 수", Status.UNMET, "필수 조건 하루 " + want + "곳과 달라요 · " + String.join(" · ", counts));
        return new Check("하루 방문 수", Status.NEEDS_CHECK, "선호 조건 하루 " + want + "곳은 시간·후보에 맞춰 가능한 만큼만 반영했어요 · " + String.join(" · ", counts));
    }

    // 그날 첫 숙소 도착(STAY) 뒤에 있는 방문은 저녁 관광이다.
    private static long eveningVisits(Day day) {
        boolean afterStay = false; long count = 0;
        for (var item : day.items()) {
            if ("STAY".equals(item.type())) afterStay = true;
            else if (afterStay && "VISIT".equals(item.type())) count++;
        }
        return count;
    }

    // 고른 여행 취향마다 맞는 관광지가 일정에 들어갔는지 본다. 취향은 선호 조건이라 없으면 미충족이 아니라 확인 필요로 둔다.
    // 판단은 장소 분류·이름 기준이다(ThemePlaces). 맛집은 식사 장소 취향이라 여기서 보지 않는다.
    private static Check themes(Input input, Result result) {
        var themes = ThemePlaces.placeThemes(input.conditions().themeNames());
        if (themes.isEmpty()) return new Check("여행 취향", Status.NOT_APPLICABLE, "관광지로 반영할 여행 취향을 고르지 않았어요.");
        var candidates = new HashMap<Long, com.travel.travel_recommendation.dto.PlaceCandidate>();
        input.attractionsWithReserve().forEach(p -> candidates.put(p.placeId(), p));
        var visited = visits(result).stream().map(i -> candidates.get(i.placeId())).filter(Objects::nonNull).distinct().toList();
        var found = new ArrayList<String>(); var missing = new ArrayList<String>();
        for (var theme : themes) {
            var names = visited.stream().filter(p -> !ThemePlaces.matched(p.name(), p.subtype(), List.of(theme)).isEmpty()).map(p -> p.name()).toList();
            if (names.isEmpty()) missing.add(theme); else found.add(theme + " " + names.size() + "곳(" + String.join(", ", names) + ")");
        }
        String basis = " 장소 분류와 이름으로 판단했어요.";
        if (missing.isEmpty()) return new Check("여행 취향", Status.MET, String.join(" · ", found) + " ·" + basis);
        return new Check("여행 취향", Status.NEEDS_CHECK, (found.isEmpty() ? "" : String.join(" · ", found) + " · ")
            + String.join("·", missing) + "에 맞는 관광지는 확인된 후보에서 찾지 못했어요." + basis);
    }

    // 숙박하는 날은 숙소로 가는 이동부터 저녁 관광·숙소 복귀까지 저녁 한도(22시)를 쓴다. 관광은 그날 관광을 마치는 시각 안이어야 한다.
    // 첫날 집 출발과 마지막 날 귀가 이동은 정한 집 출발·귀가 시각을 기준으로 본다.
    private static Check timeline(Input input, Result result) {
        var evening = TripConstraints.eveningLimit(input.end());
        boolean eveningUsed = false;
        var home = input.home();
        for (var day : result.days()) {
            boolean first = day.number() == 1, last = day.number() == result.days().size();
            LocalTime end = home != null && first ? home.departAt() : input.dayStart(day.number());
            LocalTime dayEnd = input.dayEnd(day.number());
            boolean stayDay = input.places().accommodationNeeded() && !last;
            int stayAt = stayDay ? ItineraryGenerator.stayIndex(day.items()) : -1;
            int eveningFrom = ItineraryGenerator.eveningFrom(day.items(), stayAt);
            for (int i = 0; i < day.items().size(); i++) {
                var item = day.items().get(i);
                boolean homeLeg = home != null && item.placeId() == null;
                boolean afterTour = stayAt >= 0 && i >= eveningFrom;
                var limit = homeLeg ? (last && i == day.items().size() - 1 ? home.arriveBy() : dayEnd) : afterTour ? TripConstraints.eveningLimit(dayEnd) : dayEnd;
                if (item.start() == null || item.end() == null || item.start().isBefore(end) || !item.end().isAfter(item.start()))
                    return new Check("시간 충돌", Status.UNMET, day.number() + "일차 '" + item.name() + "' 시간이 앞 활동과 겹쳐요.");
                if (item.end().isAfter(limit))
                    return new Check("시간 충돌", Status.UNMET, day.number() + "일차 '" + item.name() + "'이(가) "
                        + (homeLeg ? "정한 귀가 시각 " : afterTour ? "저녁 한도 " : "하루 종료 시간 ") + limit + "을 넘어요.");
                if (afterTour && item.end().isAfter(dayEnd)) eveningUsed = true;
                end = item.end();
            }
        }
        String homeText = home == null ? "" : " 첫날은 " + home.departAt() + " 집 출발 뒤 " + input.dayStart(1) + "부터, 마지막 날은 "
            + home.arriveBy() + " 귀가에 맞춰 " + input.dayEnd(result.days().size()) + "까지 관광해요.";
        if (eveningUsed) return new Check("시간 충돌", Status.MET, "관광은 " + input.start() + "~" + input.end()
            + " 안이고, 숙박하는 날 숙소 도착·저녁 식사·저녁 관광은 " + evening + "까지예요." + homeText + " 겹치는 활동은 없어요.");
        return new Check("시간 충돌", Status.MET, "모든 활동이 " + input.start() + "~" + input.end() + " 안에서 겹치지 않아요." + homeText);
    }

    private static Check travelTimes(Input input, Result result) {
        // 집 ↔ 여행지 이동(장소 없는 이동 항목)은 카카오 구간 조회가 아니라 집 출발·귀가 설정의 편도 시간이라 따로 적는다.
        var trip = input.home();
        String home = trip == null ? "" : " 집 → 여행지는 편도 약 " + trip.minutes() + "분(" + trip.basis() + "), 여행지 → " + trip.returnPlace()
            + "는 약 " + trip.returnMinutes() + "분(" + trip.returnBasis() + ")에 여유 10분을 더했어요.";
        var moves = result.days().stream().flatMap(d -> d.items().stream()).filter(i -> "TRANSIT".equals(i.type()) && i.placeId() != null).toList();
        if (moves.isEmpty()) return new Check("이동시간", Status.NOT_APPLICABLE, "장소 사이 이동이 없어요." + home);
        long unknown = moves.stream().filter(i -> i.travelSeconds() == null || i.checkedAt() == null).count();
        if (unknown > 0) return new Check("이동시간", Status.NEEDS_CHECK,
            "이동 " + moves.size() + "구간 중 " + unknown + "구간은 같은 주소 안 이동이라 실제 내부 동선을 확인해야 해요." + home);
        long walking = moves.stream().filter(i -> i.note() != null && i.note().startsWith(ItineraryGenerator.WALK_NOTE_PREFIX)).count();
        if (walking > 0) return new Check("이동시간", Status.NEEDS_CHECK,
            "이동 " + moves.size() + "구간 중 " + walking + "구간은 가까워서 대중교통 경로가 없어 도보 시간을 직선거리로 추정했어요. 실제 보행 경로를 확인해 주세요." + home);
        return new Check("이동시간", Status.MET, "이동 " + moves.size() + "구간 모두 카카오 예상 이동시간과 여유 10분을 반영했어요. 실시간 교통은 보장하지 않아요." + home);
    }

    private static Check operating(Input input, Result result) {
        var pool = new ArrayList<>(input.attractionsWithReserve());
        pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        int checked = 0, unknown = 0;
        for (var day : result.days()) for (var item : day.items()) {
            if (!Set.of("VISIT", "MEAL", "STAY").contains(item.type())) continue;
            var place = pool.stream().filter(p -> p.placeId().equals(item.placeId())).findFirst().orElse(null);
            if (place == null) return new Check("운영시간·휴무", Status.UNMET, "'" + item.name() + "'은(는) 후보에 없는 장소예요.");
            try {
                String status = OperatingHoursValidator.check(place.name(), place.operatingInfo(), day.date(), item.start(), item.end());
                if (status.startsWith("확인 필요")) unknown++; else checked++;
            } catch (RecommendationException e) {
                return new Check("운영시간·휴무", Status.UNMET, e.getMessage());
            }
        }
        if (unknown > 0) return new Check("운영시간·휴무", Status.NEEDS_CHECK,
            "장소 " + (checked + unknown) + "곳 중 " + unknown + "곳은 운영정보가 없거나 자동 판단할 수 없어 방문 전 확인이 필요해요.");
        return new Check("운영시간·휴무", Status.MET, "장소 " + checked + "곳이 안내된 운영시간·정기휴무와 맞아요. 임시 휴무는 확인해 주세요.");
    }

    private static Check accommodation(Input input, Result result) {
        if (!input.places().accommodationNeeded() || result.days().size() < 2)
            return new Check("숙박", Status.NOT_APPLICABLE, "숙박이 필요하지 않은 일정이에요.");
        boolean dinner = false, evening = false;
        for (var day : result.days().subList(0, result.days().size() - 1)) {
            // 첫날 맨 앞의 집 출발 이동은 숙소 뒤 순서 검사에서 뺀다.
            var items = day.items().stream().filter(i -> i.placeId() != null).toList();
            int stayAt = ItineraryGenerator.stayIndex(items);
            if (stayAt < 0 || !ItineraryGenerator.afterStayIsEveningOnly(items, stayAt))
                return new Check("숙박", Status.UNMET, day.number() + "일차가 숙소에서 끝나지 않아요.");
            if (stayAt < items.size() - 1) dinner = true;
            if (items.subList(stayAt + 1, items.size()).stream().anyMatch(i -> "VISIT".equals(i.type()))) evening = true;
        }
        return new Check("숙박", Status.NEEDS_CHECK, "마지막 날을 제외한 매일 숙소로 돌아가요."
            + (dinner ? " 숙소에 도착해 쉬었다가 숙소 근처에서 저녁을 먹어요." : "")
            + (evening ? " 저녁을 먹은 뒤 가까운 곳에서 저녁 관광을 하고 숙소로 돌아와요." : "")
            + " 체크인 시간과 예약 가능 여부는 확인해 주세요.");
    }

    // 가격 자료를 모으지 않으므로 예산은 충족으로 확정하지 않는다.
    private static Check budget(Input input) {
        var budget = input.conditions().budget();
        var c = input.constraints();
        if (budget == null) {
            // 예산 대신 숙소 예산대를 골랐으면 함께 적는다(SFR-03-03). 유형 기준이라 요금 충족은 판단하지 않는다.
            // 예산대 이름(절약형·보통·여유형)은 모두 받침으로 끝나 조사 '을'을 쓴다.
            if (c != null && c.stayTier() != null)
                return new Check("예산", Status.NOT_APPLICABLE, "예산을 입력하지 않았어요. 숙소 예산대 '"
                    + BudgetGuide.StayTier.valueOf(c.stayTier()).getLabel() + "'을 골랐어요(숙소 유형 기준, 요금 확인 필요).");
            return new Check("예산", Status.NOT_APPLICABLE, "예산을 입력하지 않았어요.");
        }
        // 예산은 전체 인원의 총예산으로 받는다. 예상 경비는 추정값이라 충족 여부를 정하지 않고 비교만 권한다.
        return new Check("예산", Status.NEEDS_CHECK, "입력 예산 " + String.format("%,d원", budget.longValue())
            + " · 예산 충족 여부는 판단하지 않았어요. 아래 예상 경비(추정)와 비교해 직접 확인해 주세요.");
    }

    private static List<Item> visits(Result result) {
        return result.days().stream().flatMap(d -> d.items().stream()).filter(i -> "VISIT".equals(i.type())).toList();
    }
}
