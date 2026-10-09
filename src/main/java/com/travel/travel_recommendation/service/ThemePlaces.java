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

    /** 분류 맨 앞이 문화·역사여도 문화유산을 먼저 고르지 않을 여행지 이름. */
    private static final Set<String> NOT_HERITAGE_CITIES = Set.of("대전");

    /**
     * 여행지의 대표 분류가 문화·역사이면(예: 경주 "문화·역사,자연", 공주 "문화·역사") 관광지 순서를 정할 때 쓰는 취향에 문화·역사를 더한다.
     * 역사 도시에서는 사용자가 취향을 따로 고르지 않아도 문화유산을 다른 지역보다 먼저 고르기 위해서다.
     * 사용자가 고른 취향 뒤에 붙여 사용자의 취향이 먼저 반영되게 하고, 대표 분류(맨 앞)가 아니면 더하지 않는다.
     * 대전은 분류 맨 앞이 문화·역사지만 과학 전시 위주라 역사 도시로 보지 않는다(NOT_HERITAGE_CITIES).
     */
    public static List<String> withHeritageDestination(List<String> themes, String destinationName, String destinationSubtype) {
        var chosen = themes == null ? List.<String>of() : themes;
        String lead = destinationSubtype == null ? "" : destinationSubtype.split(",")[0].strip();
        if (!"문화·역사".equals(lead) || chosen.contains("문화·역사") || NOT_HERITAGE_CITIES.contains(destinationName)) return chosen;
        var result = new ArrayList<>(chosen); result.add("문화·역사");
        return List.copyOf(result);
    }

    // 여행 목적에 절·사찰·성당을 직접 적었는지 본다. '절'은 '계절'·'친절' 같은 말과 섞이지 않게 앞뒤 글자를 확인한다.
    private static final java.util.regex.Pattern RELIGIOUS_PURPOSE = java.util.regex.Pattern.compile(
        "사찰|성당|템플스테이|성지순례|불교|천주교|(?<![가-힣])절(에서|에|을|이|도|과|로|은)?(?![가-힣])");

    /** 종교 시설을 뒤로 보내지 않을 경우: 문화·역사 취향(역사 도시라 더한 것 포함)이거나 목적에 절·사찰·성당을 적은 경우다. */
    public static boolean wantsReligiousSites(List<String> themes, String purpose) {
        return (themes != null && themes.contains("문화·역사")) || (purpose != null && RELIGIOUS_PURPOSE.matcher(purpose).find());
    }

    /**
     * 사용자가 원하지 않았는데 사찰·성당이 일정에 여러 곳 들어가지 않게 한다. 종교 시설은 맨 앞의 한 곳만 제자리에 두고
     * (예: 경주의 불국사) 나머지는 맨 뒤로 보낸다. 일정은 앞에서부터 하루 방문 수만큼 고르므로 뒤로 간 곳은 보통 빠진다.
     * 사용자가 원했으면(wantsReligiousSites) 순서를 바꾸지 않고, keepIds(목적 시설·축제 장소)는 옮기지 않는다.
     */
    public static List<PlaceCandidate> religiousLast(List<PlaceCandidate> ordered, List<String> themes, String purpose, Collection<Long> keepIds) {
        if (wantsReligiousSites(themes, purpose)) return ordered;
        var front = new ArrayList<PlaceCandidate>(); var back = new ArrayList<PlaceCandidate>();
        boolean keptOne = false;
        for (var place : ordered) {
            if (!place.religiousSite() || keepIds.contains(place.placeId())) front.add(place);
            else if (!keptOne) { front.add(place); keptOne = true; }
            else back.add(place);
        }
        if (back.isEmpty()) return ordered;
        front.addAll(back);
        return List.copyOf(front);
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
