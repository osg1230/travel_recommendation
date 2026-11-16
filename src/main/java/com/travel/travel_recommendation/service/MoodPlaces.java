package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.List;
import java.util.Map;

/**
 * 여행 분위기(MOOD 키워드: 한적한·활기찬·감성적인)와 어울리는 관광지 종류(2026-11-16, 취향·감성 반영 계획 3단계).
 * 실제로 붐비는 정도나 분위기는 외부 데이터로 알 수 없어, 분류 이름·장소 이름에 들어간 낱말로 어울리는 종류를 추정한다.
 * 그래서 화면에는 "한적한 곳"이라고 단정하지 않고 "고른 분위기와 어울리는 종류"라고만 쓴다.
 */
public final class MoodPlaces {
    private MoodPlaces() {}

    public static final String QUIET = "한적한", LIVELY = "활기찬", EMOTIONAL = "감성적인";

    /** 분위기 이름 → 분류·장소 이름에 이 낱말이 있으면 어울리는 종류로 본다. */
    private static final Map<String, List<String>> WORDS = Map.of(
        // 조용히 걷거나 쉬는 곳
        QUIET, List.of("수목원", "휴양림", "숲", "둘레길", "해파랑길", "바우길", "산책로", "나눔길", "호수", "저수지", "계곡", "정원", "사찰", "서원", "향교", "습지", "생태"),
        // 사람이 모이고 볼거리·먹을거리가 많은 곳
        // 해수욕장·해변은 활기찬·감성적인 둘 다에 걸려 바다 여행에서 분위기 차이가 없어져 넣지 않는다(2026-11-16 비교).
        LIVELY, List.of("시장", "먹자골목", "거리", "테마파크", "놀이", "유원지", "워터파크", "아쿠아리움", "광장", "케이블카", "루지", "레일바이크"),
        // 풍경·전시·공간의 분위기를 즐기는 곳
        EMOTIONAL, List.of("카페", "미술관", "갤러리", "전시", "정원", "한옥", "전망대", "스카이워크", "등대", "해안", "출렁다리", "책방", "서점"));

    /**
     * 장소 목록을 모을 때 분위기별로 카카오에서 더 찾을 검색어("○○ 수목원"처럼). 취향·대표 관광지로만 찾으면
     * 분위기에 맞는 종류가 후보에 거의 없었다(강릉 바다 여행의 한적한 후보 0~1곳, 2026-11-16 비교).
     * 카카오 '여행 > 관광,명소' 분류만 받으므로(KakaoMapClient.searchThemeAttractions) 그 분류로 나오는 낱말만 둔다.
     */
    private static final Map<String, List<String>> SEARCH_WORDS = Map.of(
        QUIET, List.of("수목원", "둘레길"),
        LIVELY, List.of("테마파크", "유원지"),
        EMOTIONAL, List.of("카페거리", "전망대"));

    /**
     * 일정 후보를 고를 때 취향 장소보다 앞에 둘 분위기 관광지 수. 후보는 취향에 맞는 곳부터 정해진 수만 남기므로,
     * 분위기 검색으로 찾은 곳(검색 순서가 뒤쪽)은 이렇게 앞에 두지 않으면 잘렸다(2026-11-16 확인, 강릉솔향수목원).
     */
    public static final int CANDIDATE_KEEP = 3;

    /**
     * 분위기 검색으로 찾는 종류. 검색어와 같고, 둘레길은 카카오가 해파랑길·바우길로 돌려줘 그 이름도 넣는다.
     * 후보 앞에 둘 곳은 이 종류에서만 고른다. 어울리는 종류 전체(WORDS)에서 고르면 시장·전시관처럼 이미 후보에 있던 곳이 먼저 뽑혀
     * 검색으로 새로 찾은 곳(카페거리·테마파크)이 여전히 잘렸다(2026-11-16 확인).
     */
    private static final Map<String, List<String>> SEARCHED_KINDS = Map.of(
        QUIET, List.of("수목원", "둘레길", "해파랑길", "바우길"),
        LIVELY, List.of("테마파크", "유원지"),
        EMOTIONAL, List.of("카페거리", "전망대"));

    /** 분위기 검색으로 찾는 종류인지(이름·분류 글자). 일정 후보 앞에 둘 곳을 고를 때 쓴다. */
    public static boolean searchedKind(String name, String subtype, List<String> moods) {
        String text = (name == null ? "" : name) + " " + (subtype == null ? "" : subtype);
        return moods.stream().flatMap(m -> SEARCHED_KINDS.getOrDefault(m, List.of()).stream()).anyMatch(text::contains);
    }

    /** 고른 분위기의 검색어. 고른 순서대로이고 겹치는 낱말은 한 번만 넣는다. */
    public static List<String> searchWords(List<String> moods) {
        return moods.stream().flatMap(m -> SEARCH_WORDS.getOrDefault(m, List.of()).stream()).distinct().toList();
    }

    /** 고른 분위기 이름 목록. 고르지 않았으면 비어 있다. */
    public static List<String> chosen(ConfirmedTravelConditions conditions) {
        if (conditions == null || conditions.selectedKeywords() == null) return List.of();
        return conditions.selectedKeywords().stream().filter(k -> "MOOD".equals(k.categoryCode())).map(k -> k.name()).toList();
    }

    /** 고른 분위기 가운데 이곳과 어울리는 첫 분위기. 없으면 null이다. */
    public static String matched(PlaceCandidate place, List<String> moods) {
        return matched(place.name(), place.subtype(), moods);
    }

    /** 이름·분류 글자로 본 {@link #matched(PlaceCandidate, List)}. DB 장소(Place)에도 쓴다. */
    public static String matched(String name, String subtype, List<String> moods) {
        String text = (name == null ? "" : name) + " " + (subtype == null ? "" : subtype);
        for (String mood : moods) {
            if (WORDS.getOrDefault(mood, List.of()).stream().anyMatch(text::contains)) return mood;
        }
        return null;
    }

    /**
     * 분위기 장소를 여행 일수만큼 낮 관광지 순서 앞쪽으로 당긴다(하루 1곳 보장, 2026-11-16 사용자 결정).
     * 일정은 이 순서의 앞쪽부터 낮 관광지를 고르므로, 앞으로 당긴 곳은 일정에 들어간다. 어느 날에 들어갈지는 동선으로 정해져
     * 날마다 꼭 한 곳씩이 아니라 여행 전체에서 일수만큼이다.
     * 분위기 검색으로 찾은 종류(수목원·카페거리 등)를 먼저 고르고, 그다음은 어울리는 종류를 원래 순서대로 고른다.
     * avoid(AI가 뺀 곳, 다시 만들기에서 뒤로 보낸 곳)는 당기지 않는다. front(필수·중심·목적 장소)가 맨 앞에 있으면 그 뒤에 둔다.
     */
    public static List<PlaceCandidate> moodFirst(List<PlaceCandidate> ordered, List<String> moods, int count,
                                                 java.util.Set<Long> avoid, java.util.Set<Long> front) {
        if (moods.isEmpty() || count <= 0) return ordered;
        var matched = ordered.stream().filter(p -> !avoid.contains(p.placeId()) && matched(p, moods) != null).toList();
        var searched = matched.stream().filter(p -> searchedKind(p.name(), p.subtype(), moods)).toList();
        var moved = new java.util.ArrayList<PlaceCandidate>(searched);
        matched.stream().filter(p -> !searched.contains(p)).forEach(moved::add);
        var pulled = moved.stream().limit(count).toList();
        if (pulled.isEmpty()) return ordered;
        var result = new java.util.ArrayList<PlaceCandidate>();
        int i = 0;
        while (i < ordered.size() && front.contains(ordered.get(i).placeId())) result.add(ordered.get(i++));
        result.addAll(pulled);
        for (; i < ordered.size(); i++) if (!pulled.contains(ordered.get(i))) result.add(ordered.get(i));
        return result;
    }

    /** 하루를 마무리하기 좋은 풍경 장소의 낱말(바다·전망). 감성적인 여행의 하루 흐름에 쓴다. */
    private static final List<String> SCENERY_ENDING = List.of("해수욕장", "해변", "해안", "등대", "전망대", "스카이워크");
    /** 마지막으로 옮겨서 늘어나도 되는 하루 이동 거리(직선, km). 이보다 늘면 동선이 나빠져 옮기지 않는다. */
    public static final double ENDING_EXTRA_KM = 5.0;
    /** 하루 마무리로 옮긴 곳의 고른 이유. */
    public static final String ENDING_NOTE = "하루를 바다·전망 풍경으로 마무리하도록 마지막에 둠";

    public static boolean sceneryEnding(PlaceCandidate place) {
        String text = (place.name() == null ? "" : place.name()) + " " + (place.subtype() == null ? "" : place.subtype());
        return SCENERY_ENDING.stream().anyMatch(text::contains);
    }

    /**
     * 감성적인 여행의 하루 흐름(2026-11-16, 계획 5.3): 그날 관광지 중 바다·전망 장소를 하루 마지막으로 옮긴 순서.
     * 이미 마지막이 풍경 장소이거나, 풍경 장소가 없거나, 옮기면 하루 거리(length)가 ENDING_EXTRA_KM보다 늘면 null이다.
     * 풍경 장소가 여럿이면 옮긴 뒤 거리가 가장 짧은 곳을 고른다. length는 순서를 받아 하루 거리를 돌려준다.
     */
    public static <T extends PlaceCandidate> List<T> sceneryLast(List<T> order, java.util.function.ToDoubleFunction<List<T>> length) {
        if (order.size() < 2 || sceneryEnding(order.getLast())) return null;
        double before = length.applyAsDouble(order);
        List<T> best = null; double bestLength = Double.MAX_VALUE;
        for (T place : order) {
            if (!sceneryEnding(place)) continue;
            var moved = new java.util.ArrayList<T>(order); moved.remove(place); moved.add(place);
            double after = length.applyAsDouble(moved);
            if (after < bestLength) { bestLength = after; best = moved; }
        }
        return best != null && bestLength <= before + ENDING_EXTRA_KM ? best : null;
    }
}
