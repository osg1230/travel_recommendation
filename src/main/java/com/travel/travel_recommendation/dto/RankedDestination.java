package com.travel.travel_recommendation.dto;

import java.util.List;

/**
 * 지역명과 출처는 서버 스냅샷을 사용하고 GPT에서는 순서·이유와, 이유와 관련된 장소 이름(centerPlaces)만 받는다.
 * centerPlaces는 일정의 중심 장소를 고르는 근거로만 쓰며, 실제 후보와 이름이 맞는 곳만 쓴다. 이전 JSON에는 없다.
 */
public record RankedDestination(int rank, DestinationCandidate destination, String reason, List<String> centerPlaces) {
    public RankedDestination {
        centerPlaces = centerPlaces == null ? List.of() : List.copyOf(centerPlaces);
    }
    public RankedDestination(int rank, DestinationCandidate destination, String reason) {
        this(rank, destination, reason, List.of());
    }
}
