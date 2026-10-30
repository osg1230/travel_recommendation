package com.travel.travel_recommendation.dto;

import java.time.Instant;
import java.util.List;

/**
 * 자연어 요청에서 여행지 후보가 5곳보다 많을 때, AI가 여행지마다 관광공사 등록 장소 근거를 보고 조건에 맞게 고른 여행지.
 * 추천 요청 스냅샷에 함께 보관해 후보 조회와 저장 직전 재확인이 같은 후보를 쓰게 한다.
 * destinationIds는 잘 맞는 순서다. status: PICKED(고름), SKIPPED(AI 설정 없음), FAILED(고르기 실패, 기존 기준으로 후보 선택).
 */
public record DestinationPick(String status, List<Long> destinationIds, String message, Instant checkedAt) {
    public DestinationPick {
        destinationIds = destinationIds == null ? List.of() : List.copyOf(destinationIds);
    }
}
