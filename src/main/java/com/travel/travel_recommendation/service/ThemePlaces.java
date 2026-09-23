package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.*;

/**
 * 사용자가 고른 여행 취향(자연·바다·휴식·문화·역사·체험·액티비티)에 관광지가 맞는지 장소 분류와 이름으로 판단한다.
 * 분류는 카카오의 "여행 > 관광,명소 > 문화유적 > 사당,제단" 같은 값이나 시스템 장소의 "박물관·문화유산" 같은 값이다.
 * 맛집은 식사 장소 취향이라 관광지 판단에 쓰지 않는다. 판단은 분류 글자 기준이라 실제 분위기를 확인한 것은 아니다.
 */
public final class ThemePlaces {
    private ThemePlaces() {}

    /** exact는 분류의 한 단계가 정확히 같아야 맞는 말(예: "산"), words는 분류나 이름에 들어 있으면 맞는 말이다. */
    private record Rule(Set<String> exact, List<String> words) {}

    private static final Map<String, Rule> RULES = new LinkedHashMap<>();
    static {
        RULES.put("자연", new Rule(Set.of("산", "호수", "계곡", "폭포", "숲", "저수지", "오름", "강"),
            List.of("공원", "수목원", "식물원", "휴양림", "자연", "정원", "습지", "생태", "숲길")));
        RULES.put("바다", new Rule(Set.of("섬", "등대", "항구", "해안"),
            List.of("해수욕장", "해변", "바닷가", "해안", "포구", "등대", "해상", "바다")));
        RULES.put("휴식", new Rule(Set.of("호수"),
            List.of("공원", "산책", "둘레길", "나눔길", "온천", "정원", "휴양림", "수목원", "도보여행")));
        RULES.put("문화·역사", new Rule(Set.of(),
            List.of("문화유적", "유적", "박물관", "기념관", "미술관", "전시", "향교", "서원", "사찰", "성당", "한옥",
                "고택", "생가", "궁", "성곽", "산성", "문화유산", "누각", "역사")));
        RULES.put("체험·액티비티", new Rule(Set.of(),
            List.of("테마파크", "유원지", "레일바이크", "케이블카", "루지", "체험", "놀이", "레저", "아쿠아리움", "짚라인", "동물원")));
    }

    /** 고른 취향 중 관광지 선택에 쓰는 것만 돌려준다(맛집 등은 뺀다). */
    public static List<String> placeThemes(List<String> themes) {
        return themes == null ? List.of() : themes.stream().filter(RULES::containsKey).distinct().toList();
    }

    /** 이 장소가 맞는 취향. themes 중에서만 찾는다. */
    public static List<String> matched(String name, String subtype, List<String> themes) {
        var segments = Arrays.stream((subtype == null ? "" : subtype).split(">")).map(String::strip).toList();
        String text = (subtype == null ? "" : subtype) + " " + (name == null ? "" : name);
        return placeThemes(themes).stream().filter(theme -> {
            var rule = RULES.get(theme);
            return segments.stream().anyMatch(rule.exact()::contains) || rule.words().stream().anyMatch(text::contains);
        }).toList();
    }

    public static boolean matches(PlaceCandidate place, List<String> themes) {
        return !matched(place.name(), place.subtype(), themes).isEmpty();
    }

    /**
     * 취향에 맞는 관광지를 앞으로 옮긴다. 여러 취향을 골랐으면 취향마다 돌아가며 한 곳씩 앞에 두어, 후보가 많은
     * 취향(예: 공원이 많은 휴식)이 앞자리를 모두 차지하지 않게 한다. 같은 취향 안의 순서(가까운 순 등)는 그대로 둔다.
     * 예) 취향 휴식·문화·역사, 후보 공원A·공원B·향교·호수 → 공원A·향교·공원B·호수
     */
    public static List<PlaceCandidate> themeFirst(List<PlaceCandidate> ordered, List<String> themes) {
        var wanted = placeThemes(themes);
        if (wanted.isEmpty()) return ordered;
        Map<String, Deque<PlaceCandidate>> byTheme = new LinkedHashMap<>();
        wanted.forEach(theme -> byTheme.put(theme, new ArrayDeque<>()));
        for (var place : ordered) for (var theme : matched(place.name(), place.subtype(), wanted)) byTheme.get(theme).add(place);
        var result = new ArrayList<PlaceCandidate>(); var used = new HashSet<Long>();
        boolean added = true;
        while (added) {
            added = false;
            for (var queue : byTheme.values()) {
                while (!queue.isEmpty() && used.contains(queue.peek().placeId())) queue.poll();
                var next = queue.poll();
                if (next != null) { result.add(next); used.add(next.placeId()); added = true; }
            }
        }
        for (var place : ordered) if (used.add(place.placeId())) result.add(place);
        return List.copyOf(result);
    }
}
