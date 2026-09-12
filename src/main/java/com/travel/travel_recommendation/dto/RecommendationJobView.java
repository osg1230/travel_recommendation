package com.travel.travel_recommendation.dto;
import java.util.*;
public record RecommendationJobView(UUID jobId, String status, String message, String errorCode,
        List<DestinationCandidate> candidates, boolean pending, List<RankedDestination> recommendations,
        boolean canRecommend, int attemptCount) {}
