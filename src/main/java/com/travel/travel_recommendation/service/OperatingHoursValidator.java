package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import java.time.*;
import java.util.Set;
import java.util.regex.Pattern;

/** 단순한 원문만 검사한다. 공휴일·입장 마감·부분 휴관 등을 추측해서 생략하지 않는다. */
public final class OperatingHoursValidator {
    private OperatingHoursValidator() {}
    private static final Pattern HOURS = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)\\s*[~～–-]\\s*([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern CLOSED = Pattern.compile("^매주\\s*([월화수목금토일])요일$");
    public static boolean fresh(PlaceOperatingInfo info, Instant now) {
        return info != null && info.fetchedAt() != null && !info.fetchedAt().isAfter(now)
            && info.fetchedAt().isAfter(now.minus(Duration.ofHours(24)))
            && info.hours() != null && info.closedDays() != null && info.checkIn() != null && info.checkOut() != null;
    }

    /** 폐업 안내를 믿는 기간. 지나면 후보로 돌아와 운영정보를 다시 조회할 수 있다. */
    static final Duration CLOSED_DOWN_VALID = Duration.ofDays(30);

    /**
     * 관광공사 운영시간·휴무 안내문에 '폐업'이 적혀 있는지. 이런 장소는 일정 후보에서 뺀다(PlaceCandidateService.usable).
     * '영업종료 30분 전 주문 마감', '폐관 1시간 전 입장'처럼 평소 안내에도 쓰는 말은 폐업으로 보지 않는다.
     * 운영정보를 조회한 장소만 알 수 있고, 안내문에 적혀 있지 않은 폐업은 알 수 없다.
     */
    public static boolean closedDown(PlaceOperatingInfo info, Instant now) {
        if (info == null || info.fetchedAt() == null || info.fetchedAt().isBefore(now.minus(CLOSED_DOWN_VALID))) return false;
        return (info.hours() != null && info.hours().contains("폐업")) || (info.closedDays() != null && info.closedDays().contains("폐업"));
    }

    /** 안내된 정기휴무 요일과 방문 날짜가 겹치는지만 본다. 시간대·임시 휴무는 여기서 판단하지 않는다. */
    public static boolean closedOn(PlaceOperatingInfo info, LocalDate date) {
        if (date == null || !fresh(info, Instant.now()) || "32".equals(info.contentTypeId())) return false;
        var closed = CLOSED.matcher(info.closedDays().trim());
        return closed.matches() && date.getDayOfWeek().getValue() == "월화수목금토일".indexOf(closed.group(1)) + 1;
    }

    public static String check(String name, PlaceOperatingInfo info, LocalDate date, LocalTime start, LocalTime end) {
        if (!fresh(info, Instant.now())) return "확인 필요 · 운영정보가 없거나 조회 후 24시간이 지났어요.";
        if (date == null) return "확인 필요 · 여행 날짜를 정한 후 운영 여부를 확인해 주세요.";
        if ("32".equals(info.contentTypeId())) return "확인 필요 · 숙소 입실·퇴실 안내와 예약 조건을 확인해 주세요.";
        if (closedOn(info, date))
            throw conflict(place(name) + "방문 날짜가 안내된 정기휴무일이에요. 여행 날짜나 장소를 바꿔 주세요.");
        if (!CLOSED.matcher(info.closedDays().trim()).matches() && !Set.of("연중무휴", "없음").contains(info.closedDays().trim())) {
            return "확인 필요 · 휴무 예외 또는 정보 부족으로 자동 판단하지 않았어요.";
        }
        var hours = HOURS.matcher(info.hours().trim());
        if (!hours.matches()) return "확인 필요 · 운영시간의 세부 조건을 직접 확인해 주세요.";
        LocalTime open = LocalTime.of(Integer.parseInt(hours.group(1)), Integer.parseInt(hours.group(2)));
        LocalTime close = LocalTime.of(Integer.parseInt(hours.group(3)), Integer.parseInt(hours.group(4)));
        if (!close.isAfter(open)) return "확인 필요 · 자정을 넘기는 운영시간은 별도 확인이 필요해요.";
        if (start.isBefore(open) || end.isAfter(close))
            throw conflict(place(name) + "방문 시간이 안내된 운영시간(" + info.hours().trim() + ")을 벗어나요. 하루 시작·종료 시간이나 체류시간을 조정해 주세요.");
        return "안내된 운영시간·정기휴무와 일치 · 임시 휴무와 방문 당일 운영 여부는 별도 확인해 주세요.";
    }

    /** 안내된 운영시간·정기휴무로 그 시간에 여는 것을 확인했는지. 정보가 없거나 자동 판단하지 못하면(확인 필요) false다. */
    public static boolean confirmedOpen(PlaceOperatingInfo info, LocalDate date, LocalTime start, LocalTime end) {
        try { return check("", info, date, start, end).startsWith("안내된 운영시간"); }
        catch (RecommendationException closed) { return false; }
    }

    public static String note(String name, PlaceOperatingInfo info, LocalDate date, LocalTime start, LocalTime end) {
        String status = check(name, info, date, start, end);
        if (info == null) return status;
        return status + "\n한국관광공사 TourAPI · 조회 " + info.fetchedAt()
            + "\n운영시간: " + value(info.hours()) + " / 휴무: " + value(info.closedDays())
            + "\n주차: " + value(info.parking())
            + ("32".equals(info.contentTypeId()) ? "\n입실: " + value(info.checkIn()) + " / 퇴실: " + value(info.checkOut()) : "");
    }
    private static String value(String text) { return text == null || text.isBlank() ? "정보 없음" : text; }
    // 어느 장소 때문에 일정을 만들지 못했는지 사용자가 알 수 있게 이름을 함께 알린다.
    private static String place(String name) { return name == null || name.isBlank() ? "" : "'" + name + "' "; }
    private static RecommendationException conflict(String message) { return new RecommendationException("OPERATING_CONFLICT", message); }
}
