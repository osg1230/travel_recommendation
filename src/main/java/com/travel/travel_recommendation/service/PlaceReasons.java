package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /**
     * 화면에 보여 줄 메모(2026-11-17). 서버가 적은 '고른 이유' 꼬리표는 내부 기준 이름이라(예: "고른 음식(김밥) 음식점")
     * 여행자가 읽기 어색하므로, 화면에서만 자연스러운 말로 바꿔 '추천 이유' 줄로 보여 준다(예: "김밥을 먹을 수 있는 곳").
     * 저장한 메모는 바꾸지 않는다. AI 활동 안내·수정안이 그 꼬리표를 근거로 쓰고, 이전에 만든 초안도 같은 말로 보이게 하려는 것이다.
     */
    public static String forDisplay(String note) {
        if (note == null) return null;
        List<String> lines = new ArrayList<>();
        for (String line : note.split("\n", -1))
            lines.add(line.startsWith(PREFIX) ? "추천 이유: " + friendlyTags(line.substring(PREFIX.length())) : line);
        return String.join("\n", lines);
    }

    /** " · "로 이은 꼬리표를 하나씩 바꾼다. 바꾼 말이 겹치면(예: '시간이 남아 추가'의 두 형태) 한 번만 적는다. */
    private static String friendlyTags(String tags) {
        List<String> result = new ArrayList<>();
        for (String tag : tags.split(" · ")) {
            String text = friendlyTag(tag.strip());
            if (!text.isEmpty() && !result.contains(text)) result.add(text);
        }
        return String.join(" · ", result);
    }

    /** 꼬리표 하나를 여행자가 읽는 말로 바꾼다. 꼬리표를 만드는 곳은 ItineraryGenerator(visitNote·식사·저녁 관광·예비 관광지)와 MoodPlaces다. 모르는 꼬리표는 그대로 둔다. */
    static String friendlyTag(String tag) {
        switch (tag) {
            case "필수 방문": return "꼭 가 보고 싶다고 고른 곳";
            case "수정 요청으로 추가": return "수정 요청으로 넣은 곳";
            case "수정 요청에서 고른 식당": return "수정 요청에서 고른 식당";
            case "축제 장소": return "여행 기간에 축제가 열리는 곳";
            case "일정의 중심": return "이날 일정의 중심이 되는 곳";
            case "즐겨찾기·높게 평가한 곳과 비슷한 종류": return "즐겨찾기·좋게 평가한 곳과 비슷한 곳";
            case "시간이 남아 추가", "시간이 남아 한 곳 더": return "시간 여유가 있어 더 넣은 곳";
            case "취향 후보가 모자라 채운 곳": return "고른 취향에 맞는 곳이 모자라 대신 넣은 곳";
            case "관광공사 인기 음식점": return "한국관광공사 기준 인기 맛집";
            case "AI가 꼽은 밤 명소": return "AI가 밤에 가 보기 좋다고 꼽은 곳";
            case "낮 대신 저녁 식사 뒤": return "저녁 식사 뒤에 들르도록 배치";
            case "저녁 산책·야경": return "저녁 산책·야경을 즐기기 좋은 곳";
            case MoodPlaces.ENDING_NOTE: return "바다·전망 풍경으로 하루를 마무리하기 좋은 곳";
            default: break;
        }
        Matcher m;
        if ((m = Pattern.compile("고른 음식\\((.+)\\) 음식점").matcher(tag)).matches())
            return m.group(1) + ReadableText.particle(m.group(1), "을") + " 먹을 수 있는 곳";
        if ((m = Pattern.compile("지역 대표 음식\\((.+)\\)").matcher(tag)).matches())
            return "지역 대표 음식 " + m.group(1) + ReadableText.particle(m.group(1), "을") + " 맛볼 수 있는 곳";
        if ((m = Pattern.compile("앞 끼니\\((.+)\\)와 다른 음식").matcher(tag)).matches())
            return "앞 끼니(" + m.group(1) + ")와 다른 메뉴";
        if ((m = Pattern.compile("고른 분위기\\((.+)\\)와 어울리는 종류").matcher(tag)).matches())
            return "'" + m.group(1) + "' 분위기와 어울리는 곳";
        if ((m = Pattern.compile(".+\\((.+)\\) 관련").matcher(tag)).matches())
            return "'" + m.group(1) + "'에 맞는 곳";
        if ((m = Pattern.compile("(.+) 가는 길").matcher(tag)).matches())
            return m.group(1) + " 가는 길에 있는 곳";
        return tag;
    }

    static String note(String note, String activity) {
        List<String> lines = new ArrayList<>(List.of((note == null ? "" : note).split("\n")));
        int at = -1;
        for (int i = 0; i < lines.size(); i++) if (lines.get(i).startsWith(PREFIX)) at = i;
        lines.add(at >= 0 ? at + 1 : Math.min(1, lines.size()), AI_PREFIX + activity);
        return String.join("\n", lines);
    }
}
