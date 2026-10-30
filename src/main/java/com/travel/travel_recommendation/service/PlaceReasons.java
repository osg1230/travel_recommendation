package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 일정 항목 메모에 AI가 쓴 "왜 가 볼 만한지" 한 문장을 'AI 설명' 줄로 더한다.
 * 서버가 적은 '고른 이유'(필수 방문·취향·중심 장소·시간이 남아 더 넣음 등)는 실제로 그 장소를 고른 근거라 지우지 않고,
 * AI 문장은 앞에 'AI 설명:'을 붙여 서버가 확인한 근거처럼 보이지 않게 한다(AGENTS 9절).
 */
public final class PlaceReasons {
    private PlaceReasons() {}
    static final String PREFIX = "고른 이유: ";
    /** AI가 쓴 장소 설명 줄의 시작 문구. */
    public static final String AI_PREFIX = "AI 설명: ";

    /** 일정 항목 중 AI 설명 줄이 붙은 곳이 있는지. 결과 화면 끝 안내에 쓴다. */
    public static boolean used(Result plan) {
        return plan.days().stream().flatMap(d -> d.items().stream())
            .anyMatch(i -> i.note() != null && i.note().contains("\n" + AI_PREFIX));
    }

    /** 관광·식사 항목 중 AI 이유가 있는 것만 메모에 줄을 더한다. 시각·장소·순서는 그대로다. */
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
     * 서버의 '고른 이유' 줄 바로 아래에 'AI 설명' 줄을 넣는다. 그 줄이 없으면 첫 줄(종류) 바로 아래에 넣는다.
     * 예) "관광·관람 · 해변\n고른 이유: 필수 방문 장소로 고른 곳이에요."
     *  → "관광·관람 · 해변\n고른 이유: 필수 방문 장소로 고른 곳이에요.\nAI 설명: (AI 문장)"
     */
    static String note(String note, String aiReason) {
        List<String> lines = new ArrayList<>(List.of((note == null ? "" : note).split("\n")));
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(PREFIX)) at = i;
        lines.add(at >= 0 ? at + 1 : Math.min(1, lines.size()), AI_PREFIX + aiReason);
        return String.join("\n", lines);
    }
}
