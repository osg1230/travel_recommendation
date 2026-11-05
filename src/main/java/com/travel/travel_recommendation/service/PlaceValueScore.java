package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Input;
import com.travel.travel_recommendation.dto.MemberStats;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.*;

/**
 * 관광지 가치 점수(일정 품질 개선 계획 1단계, 2026-11-05). 일정에 넣을 만한 정도를 0~100점으로 나타낸다.
 * 기준은 네 가지이고 가중치는 경험값이다.
 * - 고른 순서(40): AI가 정한 순서(실패하면 서버 규칙 순서)에서 앞일수록 높다.
 * - 대표 관광지(25): 카카오 검색 순서(PlaceRankDetails)에서 앞일수록 높다. 관광공사 인기 순서·GPT 제안 대표 관광지가 맨 앞에 저장된다.
 * - 취향 일치(25): AI가 고른 주된 활동이 고른 취향에 맞으면(활동이 없으면 분류 이름으로 판단) 만점이다.
 * - 비슷한 회원 기록(10): 비슷한 조건 회원이 저장·즐겨찾기·4점 이상 평가한 수(3명 이상이면 만점). 기록이 적어 가중치를 가장 낮게 둔다.
 * 필수·중심 장소(fixed)는 점수와 관계없이 일정에 넣는다는 규칙이 우선한다.
 * 지금은 품질 보고서에만 쓰고 일정 결과는 바꾸지 않는다. 다음 장소 고르기(2단계)에서 이 점수를 쓸 예정이다.
 */
public final class PlaceValueScore {
    static final double ORDER_WEIGHT = 40, LANDMARK_WEIGHT = 25, THEME_WEIGHT = 25, MEMBER_WEIGHT = 10;
    /** 회원 기록 만점 기준(명). */
    static final int MEMBER_FULL = 3;
    private PlaceValueScore() {}

    /** 한 관광지의 점수와 기준별 값. order·landmark·members는 0~1이고 total은 0~100이다. */
    public record Score(double total, double order, double landmark, boolean theme, double members, boolean fixed) {
        /** 보고서에 적을 한 줄. 예) 72점(순서 0.90·대표 0.60·취향 O·회원 0.00) */
        public String text() {
            return String.format("%d점(순서 %.2f·대표 %.2f·취향 %s·회원 %.2f%s)", Math.round(total), order, landmark,
                theme ? "O" : "X", members, fixed ? "·필수/중심" : "");
        }
    }

    /**
     * 고른 관광지와 예비 관광지(고른 순서대로)의 점수. ranks는 장소 ID별 카카오 검색 순서(없으면 UNRANKED),
     * members는 비슷한 조건 회원 기록이다. 저녁 관광 후보는 낮 일정과 따로 고르므로 넣지 않는다.
     */
    public static Map<Long, Score> score(Input input, Map<Long, Integer> ranks, Map<Long, MemberStats> members) {
        List<PlaceCandidate> ordered = new ArrayList<>(input.places().attractions());
        input.reserve().stream().filter(p -> !ordered.contains(p)).forEach(ordered::add);
        var themes = ThemePlaces.placeThemes(input.conditions() == null ? List.of() : input.conditions().themeNames());
        Set<Long> fixed = new HashSet<>();
        if (input.constraints() != null) { fixed.addAll(input.constraints().requiredPlaceIds()); fixed.addAll(input.constraints().centerPlaceIds()); }
        var landmarkShare = rankShares(ordered, ranks);
        Map<Long, Score> scores = new LinkedHashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            var place = ordered.get(i);
            double order = share(i, ordered.size());
            double landmark = landmarkShare.getOrDefault(place.placeId(), 0.0);
            boolean theme = matchesTheme(input, place, themes);
            double member = memberShare(members.get(place.placeId()));
            double total = ORDER_WEIGHT * order + LANDMARK_WEIGHT * landmark + (theme ? THEME_WEIGHT : 0) + MEMBER_WEIGHT * member;
            scores.put(place.placeId(), new Score(total, order, landmark, theme, member, fixed.contains(place.placeId())));
        }
        return scores;
    }

    /** n곳 중 i번째(0부터)의 앞쪽 정도. 맨 앞이 1, 맨 뒤가 0이고 한 곳뿐이면 1이다. */
    static double share(int index, int size) { return size <= 1 ? 1 : 1 - index / (double) (size - 1); }

    /** 검색 순서가 있는 곳끼리 순서를 매겨 앞쪽 정도를 준다. 순서가 없는 곳(UNRANKED)은 0이다. */
    static Map<Long, Double> rankShares(List<PlaceCandidate> places, Map<Long, Integer> ranks) {
        var ranked = places.stream().map(PlaceCandidate::placeId)
            .filter(id -> ranks.getOrDefault(id, PlaceRankDetails.UNRANKED) < PlaceRankDetails.UNRANKED)
            .sorted(Comparator.comparingInt((Long id) -> ranks.get(id)).thenComparing(id -> id)).toList();
        Map<Long, Double> shares = new HashMap<>();
        for (int i = 0; i < ranked.size(); i++) shares.put(ranked.get(i), share(i, ranked.size()));
        return shares;
    }

    /** 취향 일치. AI가 고른 활동이 있으면 그 활동으로, 없으면 분류 이름·장소 이름(ThemePlaces)으로 본다. */
    static boolean matchesTheme(Input input, PlaceCandidate place, List<String> themes) {
        if (themes.isEmpty()) return false;
        String activity = input.picking() == null ? null : input.picking().activities().get(place.placeId());
        if (activity == null) return ThemePlaces.matches(place, themes);
        return themes.stream().anyMatch(t -> OpenAiPlacePickClient.THEME_ACTIVITIES.getOrDefault(t, Set.of()).contains(activity));
    }

    static double memberShare(MemberStats stats) {
        if (stats == null) return 0;
        return Math.min(1, (stats.saved() + stats.favorited() + stats.ratedHigh()) / (double) MEMBER_FULL);
    }
}
