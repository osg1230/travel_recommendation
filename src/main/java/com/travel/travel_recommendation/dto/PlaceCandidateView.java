package com.travel.travel_recommendation.dto;

import java.util.List;
import java.util.UUID;

public record PlaceCandidateView(UUID jobId, DestinationCandidate destination,
        Integer durationDays, boolean accommodationNeeded, List<PlaceCandidate> attractions,
        List<PlaceCandidate> accommodations, List<PlaceCandidate> restaurants) {
    /** 일정을 만들기에 후보가 부족한지(관광지 5곳·음식점 3곳, 숙박 여행이면 숙소 2곳 미만). 부족하면 카카오에서 더 찾는다. */
    public boolean tooFewForSchedule() {
        return attractions.size() < 5 || restaurants.size() < 3 || (accommodationNeeded && accommodations.size() < 2);
    }
}
