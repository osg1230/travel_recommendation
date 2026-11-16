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
        QUIET, List.of("수목원", "휴양림", "숲", "둘레길", "산책로", "나눔길", "호수", "저수지", "계곡", "정원", "사찰", "서원", "향교", "습지", "생태"),
        // 사람이 모이고 볼거리·먹을거리가 많은 곳
        LIVELY, List.of("시장", "먹자골목", "거리", "테마파크", "놀이", "유원지", "워터파크", "아쿠아리움", "광장", "해수욕장", "케이블카", "루지", "레일바이크"),
        // 풍경·전시·공간의 분위기를 즐기는 곳
        EMOTIONAL, List.of("카페", "미술관", "갤러리", "전시", "정원", "한옥", "전망대", "스카이워크", "등대", "해변", "해안", "출렁다리", "책방", "서점"));

    /** 고른 분위기 이름 목록. 고르지 않았으면 비어 있다. */
    public static List<String> chosen(ConfirmedTravelConditions conditions) {
        if (conditions == null || conditions.selectedKeywords() == null) return List.of();
        return conditions.selectedKeywords().stream().filter(k -> "MOOD".equals(k.categoryCode())).map(k -> k.name()).toList();
    }

    /** 고른 분위기 가운데 이곳과 어울리는 첫 분위기. 없으면 null이다. */
    public static String matched(PlaceCandidate place, List<String> moods) {
        String text = (place.name() == null ? "" : place.name()) + " " + (place.subtype() == null ? "" : place.subtype());
        for (String mood : moods) {
            if (WORDS.getOrDefault(mood, List.of()).stream().anyMatch(text::contains)) return mood;
        }
        return null;
    }
}
