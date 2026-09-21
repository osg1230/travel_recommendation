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
        var checks = List.of(period(input, result), attractions(input, result), duplicates(result),
            dailyVisits(result), timeline(input, result), travelTimes(result), operating(input, result),
            accommodation(input, result), budget(input));
        return new Report(checks, checks.stream().noneMatch(c -> c.status() == Status.UNMET));
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

    // 필수 장소 입력은 아직 없으므로, 일정에 쓰기로 고른 관광지가 빠짐없이 들어갔는지 확인한다.
    private static Check attractions(Input input, Result result) {
        var visited = visits(result).stream().map(Item::placeId).toList();
        var missing = input.places().attractions().stream().filter(p -> !visited.contains(p.placeId())).map(p -> p.name()).toList();
        if (!missing.isEmpty()) return new Check("선택한 관광지 포함", Status.UNMET, "빠진 관광지: " + String.join(", ", missing));
        return new Check("선택한 관광지 포함", Status.MET, "후보 관광지 " + input.places().attractions().size() + "곳을 모두 배치했어요. 필수 방문 장소 지정은 아직 지원하지 않아요.");
    }

    private static Check duplicates(Result result) {
        var seen = new HashSet<Long>(); var repeated = new LinkedHashSet<String>();
        for (var item : visits(result)) if (!seen.add(item.placeId())) repeated.add(item.name());
        if (!repeated.isEmpty()) return new Check("방문 중복", Status.UNMET, "두 번 이상 배치된 관광지: " + String.join(", ", repeated));
        return new Check("방문 중복", Status.MET, "같은 관광지를 두 번 배치하지 않았어요.");
    }

    // 하루 방문 수는 사용자 입력 조건이 아니라 이동시간 계산 결과라서 비어 있는 날만 미충족으로 본다.
    private static Check dailyVisits(Result result) {
        var counts = new ArrayList<String>();
        for (var day : result.days()) {
            long visits = day.items().stream().filter(i -> "VISIT".equals(i.type())).count();
            if (visits == 0) return new Check("하루 방문 수", Status.UNMET, day.number() + "일차에 관광지가 없어요.");
            counts.add(day.number() + "일차 " + visits + "곳");
        }
        return new Check("하루 방문 수", Status.MET, String.join(" · ", counts) + " (하루 방문 수 지정은 아직 지원하지 않아요)");
    }

    private static Check timeline(Input input, Result result) {
        for (var day : result.days()) {
            LocalTime end = input.start();
            for (var item : day.items()) {
                if (item.start() == null || item.end() == null || item.start().isBefore(end) || !item.end().isAfter(item.start()))
                    return new Check("시간 충돌", Status.UNMET, day.number() + "일차 '" + item.name() + "' 시간이 앞 활동과 겹쳐요.");
                if (item.end().isAfter(input.end()))
                    return new Check("시간 충돌", Status.UNMET, day.number() + "일차 '" + item.name() + "'이(가) 하루 종료 시간 " + input.end() + "을 넘어요.");
                end = item.end();
            }
        }
        return new Check("시간 충돌", Status.MET, "모든 활동이 " + input.start() + "~" + input.end() + " 안에서 겹치지 않아요.");
    }

    private static Check travelTimes(Result result) {
        var moves = result.days().stream().flatMap(d -> d.items().stream()).filter(i -> "TRANSIT".equals(i.type())).toList();
        if (moves.isEmpty()) return new Check("이동시간", Status.NOT_APPLICABLE, "장소 사이 이동이 없어요.");
        long unknown = moves.stream().filter(i -> i.travelSeconds() == null || i.checkedAt() == null).count();
        if (unknown > 0) return new Check("이동시간", Status.NEEDS_CHECK,
            "이동 " + moves.size() + "구간 중 " + unknown + "구간은 같은 주소 안 이동이라 실제 내부 동선을 확인해야 해요.");
        return new Check("이동시간", Status.MET, "이동 " + moves.size() + "구간 모두 카카오 예상 이동시간과 여유 10분을 반영했어요. 실시간 교통은 보장하지 않아요.");
    }

    private static Check operating(Input input, Result result) {
        var pool = new ArrayList<>(input.places().attractions());
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
        for (var day : result.days().subList(0, result.days().size() - 1))
            if (day.items().isEmpty() || !"STAY".equals(day.items().getLast().type()))
                return new Check("숙박", Status.UNMET, day.number() + "일차가 숙소에서 끝나지 않아요.");
        return new Check("숙박", Status.NEEDS_CHECK, "마지막 날을 제외한 매일 숙소로 돌아가요. 체크인 시간과 예약 가능 여부는 확인해 주세요.");
    }

    // 가격 자료를 모으지 않으므로 예산은 충족으로 확정하지 않는다.
    private static Check budget(Input input) {
        var budget = input.conditions().budget();
        if (budget == null) return new Check("예산", Status.NOT_APPLICABLE, "예산을 입력하지 않았어요.");
        return new Check("예산", Status.NEEDS_CHECK, "입력 예산 " + String.format("%,d원", budget.longValue())
            + " · 식사·숙박·입장료 가격 자료가 없어 충족 여부를 판단하지 않았어요.");
    }

    private static List<Item> visits(Result result) {
        return result.days().stream().flatMap(d -> d.items().stream()).filter(i -> "VISIT".equals(i.type())).toList();
    }
}
