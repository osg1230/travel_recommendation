package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 관광 항목 메모에 AI가 쓴 "그곳에서 해 볼 만한 활동" 한 문장을 'AI 추천 활동' 줄로 더한다(2026-10-31, 전에는 'AI 설명').
 * 서버가 적은 '고른 이유'(필수 방문·일정의 중심·시간이 남아 추가 등 꼬리표)는 실제로 그 장소를 고른 근거라 지우지 않고,
 * AI 문장은 앞에 'AI 추천 활동:'을 붙여 서버가 확인한 근거처럼 보이지 않게 한다(AGENTS 9절).
 */
public final class PlaceReasons {
    private PlaceReasons() {}
    static final String PREFIX = "고른 이유: ";
    /** AI가 쓴 추천 활동 줄의 시작 문구. */
    public static final String AI_PREFIX = "AI 추천 활동: ";
    /** 2026-10-31 전에 만든 초안·저장한 일정의 AI 문장 줄. 결과 화면 안내를 보여 줄지 판단할 때만 쓴다. */
    static final String OLD_AI_PREFIX = "AI 설명: ";

    /** 일정 항목 중 AI 문장 줄이 붙은 곳이 있는지. 결과 화면 끝 안내에 쓴다. */
    public static boolean used(Result plan) {
        return plan.days().stream().flatMap(d -> d.items().stream())
            .anyMatch(i -> i.note() != null && (i.note().contains("\n" + AI_PREFIX) || i.note().contains("\n" + OLD_AI_PREFIX)));
    }

    /** 관광 항목 중 AI 추천 활동이 있는 것만 메모에 줄을 더한다. 시각·장소·순서는 그대로다. */
    public static Result apply(Result plan, Map<Long, String> activities) {
        if (activities.isEmpty()) return plan;
        List<Day> days = new ArrayList<>();
        for (var day : plan.days()) {
            List<Item> items = new ArrayList<>();
            for (var item : day.items()) {
                String activity = item.placeId() == null || !"VISIT".equals(item.type()) ? null : activities.get(item.placeId());
                items.add(activity == null ? item : new Item(item.type(), item.placeId(), item.name(), item.start(), item.end(),
                    note(item.note(), activity), item.mapUrl(), item.sourceUrl(), item.travelSeconds(), item.checkedAt()));
            }
            days.add(new Day(day.number(), day.date(), items));
        }
        return plan.withDays(days);
    }

    /**
     * 서버의 '고른 이유' 줄 바로 아래에 'AI 추천 활동' 줄을 넣는다. 그 줄이 없으면 첫 줄(종류) 바로 아래에 넣는다.
     * 예) "관광·관람 · 사찰\n고른 이유: 일정의 중심"
     *  → "관광·관람 · 사찰\n고른 이유: 일정의 중심\nAI 추천 활동: (AI 문장)"
     */
    static String note(String note, String activity) {
        List<String> lines = new ArrayList<>(List.of((note == null ? "" : note).split("\n")));
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(PREFIX)) at = i;
        lines.add(at >= 0 ? at + 1 : Math.min(1, lines.size()), AI_PREFIX + activity);
        return String.join("\n", lines);
    }
}
