package com.travel.travel_recommendation.dto;

import java.time.Instant;
import java.util.List;

/**
 * 지역을 정하지 않고 원하는 여행을 말한 요청에서, AI가 제안한 시·군 중 서버가 확인한 여행지. 추천 요청 스냅샷에 함께 보관한다.
 * categories는 원하는 여행을 나타내는 관광공사 소분류 코드(예: NA010100 산), destinationIds는 법정동 코드로 지역을 확인했고
 * 그 분류의 관광공사 등록 장소가 있는 여행지 ID다. 서비스 여행지에 없던 시·군은 확인한 뒤 여행지로 추가한 ID다.
 * status: FOUND(확인한 여행지 있음), NONE(제안은 받았지만 확인된 곳 없음), SKIPPED(AI 설정 없음), FAILED(제안 실패).
 */
public record RegionSuggestion(String status, List<String> categories, List<Long> destinationIds, String message, Instant checkedAt) {
    public RegionSuggestion {
        categories = categories == null ? List.of() : List.copyOf(categories);
        destinationIds = destinationIds == null ? List.of() : List.copyOf(destinationIds);
    }
}
