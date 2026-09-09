package com.travel.travel_recommendation.service;

public class DuplicateUserException extends RuntimeException {
    private final String field;

    public DuplicateUserException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
