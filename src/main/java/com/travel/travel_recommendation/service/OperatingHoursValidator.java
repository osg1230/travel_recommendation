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
