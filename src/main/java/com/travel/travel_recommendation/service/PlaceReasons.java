package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 일정 항목 메모의 '고른 이유'를 AI가 쓴 문장으로 바꾼다. 서버가 적은 이유는 배치 규칙 설명(시간이 남아서, 종류가 겹치지 않게 등)이라
 * 읽는 사람에게는 와닿지 않기 때문이다. 다만 필수 방문·수정 요청·축제처럼 사용자가 꼭 알아야 하는 사실과
 * 외부 자료로 확인한 근거(관광공사 연관 관광지의 인기 음식점)는 AI 문장 뒤에 그대로 남긴다.
 */
public final class PlaceReasons {
    private PlaceReasons() {}
    static final String PREFIX = "고른 이유: ";
    /** 서버 이유 중 AI 문장으로 바꿔도 남겨 둘 문장에 들어 있는 말. */
    private static final List<String> KEY_FACTS = List.of("필수 방문", "수정 요청", "행사가 열려요", "정확한 행사 장소", "연관 관광지 정보");

    /** 관광·식사 항목 중 AI 이유가 있는 것만 메모를 바꾼다. 시각·장소·순서는 그대로다. */
    public static Result apply(Result plan, Map<Long, String> reasons) {
        if (reasons.isEmpty()) return plan;
        List<Day> days = new ArrayList<>();
        for (var day : plan.days()) {
            List<Item> items = new ArrayList<>();
            for (var item : day.items()) {
                String reason = item.placeId() == null || !List.of("VISIT", "MEAL").contains(item.type()) ? null : reasons.get(item.placeId());
                items.add(reason == null ? item : new Item(item.type(), item.placeId(), item.name(), item.start(), item.end(),
                    note(item.note(), reason), item.mapUrl(), item.sourceUrl(), item.travelSeconds(), item.checkedAt()));
            }
            days.add(new Day(day.number(), day.date(), items));
        }
        return plan.withDays(days);
    }

    /**
     * 메모의 '고른 이유' 줄을 AI 이유로 바꾼다. 그 줄이 없으면 첫 줄(종류) 바로 아래에 넣는다.
     * 예) "관광·관람 · 해변\n고른 이유: 필수 방문 장소로 고른 곳이에요. 하루 시간이 남아 더 넣은 곳이에요."
     *  → "관광·관람 · 해변\n고른 이유: (AI 문장) 필수 방문 장소로 고른 곳이에요."
     */
    static String note(String note, String aiReason) {
        List<String> lines = new ArrayList<>(List.of((note == null ? "" : note).split("\n")));
        String kept = "";
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(PREFIX)) at = i;
        if (at >= 0) {
            // 문장 단위("~요.")로 나눠 꼭 알아야 하는 사실만 남긴다.
            for (String sentence : lines.get(at).substring(PREFIX.length()).split("(?<=요\\.)\\s+"))
                if (KEY_FACTS.stream().anyMatch(sentence::contains)) kept += " " + sentence;
            lines.set(at, PREFIX + aiReason + kept);
        } else lines.add(Math.min(1, lines.size()), PREFIX + aiReason);
        return String.join("\n", lines);
    }
}
