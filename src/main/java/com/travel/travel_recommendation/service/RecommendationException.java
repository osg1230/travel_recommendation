package com.travel.travel_recommendation.service;

public class RecommendationException extends RuntimeException {
    private final String code;
    public RecommendationException(String code, String message) { super(message); this.code=code; }
    public String getCode() { return code; }
}
