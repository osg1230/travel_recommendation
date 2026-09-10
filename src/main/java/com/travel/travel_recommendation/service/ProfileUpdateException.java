package com.travel.travel_recommendation.service;

import lombok.Getter;

@Getter
public class ProfileUpdateException extends RuntimeException {
    private final String field;

    public ProfileUpdateException(String field, String message) {
        super(message);
        this.field = field;
    }
}
