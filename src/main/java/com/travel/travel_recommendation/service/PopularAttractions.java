package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 관광공사 '관광지별 연관 관광지 정보'(티맵 내비게이션 이동 데이터)로 여행지의 인기 관광지 순서를 정한다.
 * 시·군·구의 여러 중심 관광지에서 "함께 많이 간 곳"으로 나온 횟수가 많을수록, 같으면 평균 순위가 높을수록 앞에 둔다.
 * 다른 사람들이 실제로 함께 간 기록으로 고르는 방식이라, 회원 평점이 적은 지금도 인기 순서를 알 수 있다.
 * 숙박·음식과 교통시설·백화점 같은 쇼핑은 빼고 전통시장은 관광지로 받는다. 여행지 밖(다른 시·군·구)의 곳은 뺀다.
 */
public final class PopularAttractions {
    static final int LIMIT = 20;
    /** 관광지로 받는 중분류. '쇼핑'은 소분류가 시장일 때만, '기타관광'은 교통시설(역·터미널·공항)을 뺀다. */
    private static final Set<String> MIDDLE = Set.of("자연관광", "역사관광", "문화관광", "레저스포츠", "체험관광", "기타관광");
    private PopularAttractions() {}

    /** districtCodes는 여행지에 속한 시·군·구 코드(5자리)다. 인기 순서대로 최대 limit개 이름을 돌려준다. */
    public static List<String> rank(List<TourApiClient.RelatedPlace> rows, Set<String> districtCodes, int limit) {
        Map<String, Set<String>> centers = new HashMap<>();
        Map<String, Integer> rankSum = new HashMap<>(), rowCount = new HashMap<>();
        for (var row : rows) {
            if (!"관광지".equals(row.large()) || !districtCodes.contains(row.districtCode())) continue;
            boolean market = "쇼핑".equals(row.middle()) && "시장".equals(row.small());
            if (!market && (!MIDDLE.contains(row.middle()) || "교통시설".equals(row.small()))) continue;
            String name = clean(row.name());
            if (KakaoMapClient.compactName(name).length() < 2) continue;
            centers.computeIfAbsent(name, key -> new HashSet<>()).add(row.center());
            rankSum.merge(name, row.rank(), Integer::sum);
            rowCount.merge(name, 1, Integer::sum);
        }
        return centers.keySet().stream()
            .sorted(Comparator.comparingInt((String name) -> -centers.get(name).size())
                .thenComparingDouble(name -> (double) rankSum.get(name) / rowCount.get(name))
                .thenComparing(name -> name))
            .limit(limit).toList();
    }

    /**
     * 카카오 검색어로 쓰기 좋게 이름을 다듬는다. "아르떼뮤지엄/강릉" → "아르떼뮤지엄 강릉",
     * "경주시 강동면 경주양동마을" → "경주양동마을"처럼 '/'를 띄어쓰기로 바꾸고 앞에 붙은 행정구역 이름은 뗀다.
     */
    static String clean(String name) {
        String value = name.replace('/', ' ').replaceAll("\\s+", " ").strip();
        return value.replaceFirst("^(\\S+(시|군|구|읍|면|동) )+", "");
    }
}
