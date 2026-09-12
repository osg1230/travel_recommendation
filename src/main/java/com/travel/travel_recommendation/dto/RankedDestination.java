package com.travel.travel_recommendation.dto;

/** 지역명과 출처는 서버 스냅샷을 사용하고 GPT에서는 순서와 이유만 받는다. */
public record RankedDestination(int rank, DestinationCandidate destination, String reason) {}
