package com.travel.travel_recommendation.dto;

import lombok.Getter;
import lombok.Setter;

/** region은 선택지 ID가 아니라 사용자가 확인할 세부 지역명이다. */
@Getter
@Setter
public class ConditionReviewForm extends TravelConditionForm {
    private String revision = "";
    private String partySize = "";
    private String accommodationNeeded = "";
    private String includeNearby = "";
}
