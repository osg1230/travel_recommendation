package com.travel.travel_recommendation.dto;

import java.util.List;
import java.util.UUID;

public record PlaceCandidateView(UUID jobId, DestinationCandidate destination,
        Integer durationDays, boolean accommodationNeeded, List<PlaceCandidate> attractions,
        List<PlaceCandidate> accommodations, List<PlaceCandidate> restaurants) {}
