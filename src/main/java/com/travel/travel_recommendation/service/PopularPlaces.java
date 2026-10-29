package com.travel.travel_recommendation.service;

import java.util.*;
import java.util.function.Predicate;

/**
 * 관광공사 '관광지별 연관 관광지 정보'(티맵 내비게이션 이동 데이터)로 여행지의 인기 관광지·음식점·숙소 순서를 정한다.
 * 시·군·구의 여러 중심 관광지에서 "함께 많이 간 곳"으로 나온 횟수가 많을수록, 같으면 평균 순위가 높을수록 앞에 둔다.
 * 다른 사람들이 실제로 함께 간 기록으로 고르는 방식이라, 회원 평점이 적은 지금도 인기 순서를 알 수 있다.
 * 여행지 밖(다른 시·군·구)의 곳은 뺀다.
 */
public final class PopularPlaces {
    static final int ATTRACTIONS = 20, RESTAURANTS = 10, STAYS = 5;
    /** 관광지로 받는 중분류. '쇼핑'은 소분류가 시장일 때만, '기타관광'은 교통시설(역·터미널·공항)을 뺀다. */
    private static final Set<String> MIDDLE = Set.of("자연관광", "역사관광", "문화관광", "레저스포츠", "체험관광", "기타관광");
    /** 끼니로 어울리지 않는 음식 소분류. 일정의 식사 고르기와 같은 기준이다(PlaceCandidate.notForMeals). */
    private static final Set<String> NOT_MEALS = Set.of("카페/찻집", "간이음식");
    /** 여행 숙소 후보로 쓰지 않는 숙박 소분류. */
    private static final Set<String> NOT_STAYS = Set.of("모텔", "캠핑");
    private PopularPlaces() {}

    /** 관광지(숙박·음식, 교통시설, 시장이 아닌 쇼핑 제외). districtCodes는 여행지에 속한 시·군·구 코드(5자리)다. */
    public static List<String> attractions(List<TourApiClient.RelatedPlace> rows, Set<String> districtCodes, int limit) {
        return rank(rows, districtCodes, limit, row -> {
            if (!"관광지".equals(row.large())) return false;
            boolean market = "쇼핑".equals(row.middle()) && "시장".equals(row.small());
            return market || (MIDDLE.contains(row.middle()) && !"교통시설".equals(row.small()));
        });
    }

    /** 음식점(카페·간이음식 제외). */
    public static List<String> restaurants(List<TourApiClient.RelatedPlace> rows, Set<String> districtCodes, int limit) {
        return rank(rows, districtCodes, limit, row -> "음식".equals(row.large()) && !NOT_MEALS.contains(row.small()));
    }

    /** 숙소(모텔·캠핑 제외). */
    public static List<String> stays(List<TourApiClient.RelatedPlace> rows, Set<String> districtCodes, int limit) {
        return rank(rows, districtCodes, limit, row -> "숙박".equals(row.large()) && !NOT_STAYS.contains(row.small()));
    }

    private static List<String> rank(List<TourApiClient.RelatedPlace> rows, Set<String> districtCodes, int limit,
                                     Predicate<TourApiClient.RelatedPlace> accepts) {
        Map<String, Set<String>> centers = new HashMap<>();
        Map<String, Integer> rankSum = new HashMap<>(), rowCount = new HashMap<>();
        for (var row : rows) {
            if (!districtCodes.contains(row.districtCode()) || !accepts.test(row)) continue;
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
