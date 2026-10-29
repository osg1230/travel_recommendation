package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 카카오 검색 결과에서 몇 번째로 나온 곳인지(0부터)를 details_json에 보관한다. 관광지 후보를 이름순이 아니라
 * 검색 결과 순서로 자르기 위해서다. 이름순으로 자르면 정동진·하슬라아트월드처럼 뒤쪽 글자로 시작하는 곳이 빠졌다.
 * 관광공사 연관 관광지(티맵 이동 데이터)의 인기 음식점·숙소로 확인한 곳은 popular도 함께 적는다.
 * details_json의 다른 정보(운영정보·사진·참고 가격)는 그대로 둔다.
 */
public final class PlaceRankDetails {
    private static final String KEY = "kakaoRank", POPULAR = "popular";
    /** 순서를 기록하지 않은 장소(시스템 장소, 순서를 저장하기 전에 수집한 곳)는 순서가 있는 곳 뒤에 둔다. */
    public static final int UNRANKED = 1000;
    private PlaceRankDetails() {}

    public static int read(Place place, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return UNRANKED;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path(KEY);
            return node.isIntegralNumber() && node.intValue() >= 0 && node.intValue() < UNRANKED ? node.intValue() : UNRANKED;
        } catch (RuntimeException error) { return UNRANKED; }
    }

    /** 인기 음식점·숙소로 확인한 곳인지. 기록이 없거나 읽지 못하면 false다. */
    public static boolean popular(Place place, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return false;
        try { return mapper.readTree(place.getDetailsJson()).path(POPULAR).asBoolean(false); }
        catch (RuntimeException error) { return false; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(순서 보관은 건너뛴다). */
    public static String write(Place place, int rank, ObjectMapper mapper) {
        return write(place, rank, false, mapper);
    }

    /** popular가 true면 인기 음식점·숙소 표시도 남긴다. false면 이전 표시를 그대로 둔다. */
    public static String write(Place place, int rank, boolean popular, ObjectMapper mapper) {
        try {
            var node = place.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(place.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            root.put(KEY, rank);
            if (popular) root.put(POPULAR, true);
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }
}
