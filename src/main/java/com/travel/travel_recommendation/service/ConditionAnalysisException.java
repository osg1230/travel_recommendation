package com.travel.travel_recommendation.service;

/** 외부 응답·원문·인증정보 대신 화면에 표시 가능한 고정 안내만 전달한다. */
public class ConditionAnalysisException extends RuntimeException {
    public ConditionAnalysisException(String message) { super(message); }
}
