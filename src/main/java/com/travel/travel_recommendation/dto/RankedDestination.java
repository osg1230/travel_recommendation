package com.travel.travel_recommendation.dto;

import java.util.List;
import java.util.Map;

/**
 * 지역명과 출처는 서버 스냅샷을 사용하고 GPT에서는 순서·이유와, 이유와 관련된 장소 이름(centerPlaces)만 받는다.
 * centerPlaces는 GPT가 돌아보기 좋은 순서로 적은 대표 코스다. 추천 카드에 '대표 코스'로 보여 주고(AI가 꼽은 순서라고 안내),
 * 일정의 중심 장소를 고를 때는 실제 후보와 이름이 맞는 곳만 쓴다. 이전 JSON에는 없다.
 * festivals는 서버가 관광공사에서 찾은, 여행 시기에 그 여행지에서 열리는 축제다(GPT가 만든 값이 아니다).
 * festivalPlaceIds는 축제 ID → 축제가 열리는 곳으로 카카오에서 확인해 저장한 관광지 ID다. 장소를 확인하지 못한 축제는 없다.
 * direction은 AI가 제안한 여러 여행 방향 중 GPT가 이 여행지에 맞다고 고른 하나다. 서버가 제안 목록에 있는 값만 받는다.
 * 사용자가 목적을 직접 말한 추천에는 없다(null). 세 값 모두 이전 JSON에는 없다.
 */
public record RankedDestination(int rank, DestinationCandidate destination, String reason, List<String> centerPlaces,
                                List<RegionalFestival> festivals, Map<String, Long> festivalPlaceIds, String direction) {
    public RankedDestination {
        centerPlaces = centerPlaces == null ? List.of() : List.copyOf(centerPlaces);
        festivals = festivals == null ? List.of() : List.copyOf(festivals);
        festivalPlaceIds = festivalPlaceIds == null ? Map.of() : Map.copyOf(festivalPlaceIds);
    }
    public RankedDestination(int rank, DestinationCandidate destination, String reason, List<String> centerPlaces) {
        this(rank, destination, reason, centerPlaces, List.of(), Map.of(), null);
    }
    public RankedDestination(int rank, DestinationCandidate destination, String reason) {
        this(rank, destination, reason, List.of());
    }
    public RankedDestination withFestivals(List<RegionalFestival> found) {
        return new RankedDestination(rank, destination, reason, centerPlaces, found, festivalPlaceIds, direction);
    }
    public RankedDestination withFestivalPlaces(Map<String, Long> placeIds) {
        return new RankedDestination(rank, destination, reason, centerPlaces, festivals, placeIds, direction);
    }
    public RankedDestination withReason(String text) {
        return new RankedDestination(rank, destination, text, centerPlaces, festivals, festivalPlaceIds, direction);
    }
    public RankedDestination withDirection(String chosen) {
        return new RankedDestination(rank, destination, reason, centerPlaces, festivals, festivalPlaceIds, chosen);
    }
}
