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
    /** AI가 추출한 여행 활동만 보관한다. 요청 바인딩으로 덮어쓸 수 없다. */
    private String travelPurpose = "";
    /** 사용자가 목적을 말하지 않아 AI가 제안한 여행 방향이면 true다. travelPurpose와 함께 서버에서만 정한다. */
    private boolean purposeSuggested;
}
