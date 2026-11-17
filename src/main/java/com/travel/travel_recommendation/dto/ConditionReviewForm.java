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
    /** 여행 목적(하고 싶은 활동). 문장 분석이 채우고, 조건 수정 화면에서 고칠 수 있다(서버가 같은 기준으로 다시 검증). */
    private String travelPurpose = "";
    /** 사용자가 목적을 말하지 않아 AI가 제안한 여행 방향이면 true다. 서버에서만 정하며, 목적을 고치면 false가 된다. */
    private boolean purposeSuggested;
    /**
     * 사용자가 가지 않겠다고 한 지역 이름. 문장 분석이 채우고, 조건 수정 화면에서는 체크를 풀어 뺄 수만 있다.
     * 화면에서 보낸 값 중 서버가 가진 목록에 있는 이름만 남기므로 새 지역을 넣을 수는 없다.
     */
    private java.util.List<String> excludedRegions = java.util.List.of();
    /** AI 보완 질문 중 조건 칸에 없는 정보(extra)를 물은 질문의 답. 확정할 때 여행 목적 뒤에 붙이고 비운다. */
    private String followUpAnswer = "";
}
