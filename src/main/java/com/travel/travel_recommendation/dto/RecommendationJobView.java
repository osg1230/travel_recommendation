package com.travel.travel_recommendation.dto;
import java.util.*;
/**
 * 추천 작업 화면과 상태 조회 응답. canContinueWithoutAi는 AI 순위를 더 받을 수 없을 때 보이는 후보로 계속할 수 있는지,
 * canRetry는 일시적인 실패라 같은 조건으로 새로 요청할 수 있는지다.
 */
public record RecommendationJobView(UUID jobId, String status, String message, String errorCode,
        List<DestinationCandidate> candidates, boolean pending, List<RankedDestination> recommendations,
        boolean canRecommend, int attemptCount, boolean canContinueWithoutAi, boolean canRetry) {}
