package com.travel.travel_recommendation.dto;

import java.time.Instant;
import java.util.List;

/**
 * 구체적 여행 목적(예: 상어 관람)에 맞는 시설을 관광공사 관광정보·카카오 장소 검색으로 찾은 결과. 추천 요청 스냅샷에 함께 보관한다.
 * keywords는 AI가 목적을 시설 검색어로 바꾼 것(예: 아쿠아리움, 수족관)이고, places는 그 검색어로 찾은 시설 중
 * 서비스 여행지 안에 있는 곳이다. 시설 이름·주소는 카카오에서 확인했지만 전시·체험 내용은 확인하지 않았다.
 * status: FOUND(시설 있음), NONE(검색했지만 여행지 안에 없음), SKIPPED(장소로 찾을 목적이 아님), FAILED(검색 실패).
 */
public record PurposeSearch(String status, List<String> keywords, List<PurposePlace> places, String message, Instant checkedAt) {
    public PurposeSearch {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        places = places == null ? List.of() : List.copyOf(places);
    }
    /** placeId는 장소 목록에 저장한 관광지 ID, destinationId는 그 시설이 있는 여행지 ID다. */
    public record PurposePlace(Long placeId, Long destinationId, String destinationName, String name, String address,
                               String category, String sourceUrl) {}

    public List<Long> destinationIds() {
        return places.stream().map(PurposePlace::destinationId).distinct().toList();
    }
    public List<PurposePlace> placesIn(Long destinationId) {
        return places.stream().filter(p -> p.destinationId().equals(destinationId)).toList();
    }
}
