package com.travel.travel_recommendation.dto;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 비슷한 조건으로 여행한 다른 회원 중 이 장소를 고른 회원 수(SimilarMemberService, 2026-10-31).
 * saved는 비슷한 추천 요청에서 이어진 저장 일정에 넣은 회원, favorited는 즐겨찾기한 회원, ratedHigh는 4점 이상으로 평가한 회원이다.
 * 여행지(시·군)는 평가 대상이 아니라 여행지의 ratedHigh는 늘 0이고, 관광지에서만 센다.
 * 회원 수만 담고 닉네임·로그인 ID·입력 원문·한줄평은 담지 않는다.
 */
public record MemberStats(int saved, int favorited, int ratedHigh) {
    public boolean empty() { return saved==0 && favorited==0 && ratedHigh==0; }

    /** AI에 보내는 회원 수. 순서를 고정해 같은 기록이면 늘 같은 입력이 되게 한다. */
    public Map<String,Integer> counts() {
        Map<String,Integer> counts=new LinkedHashMap<>();
        counts.put("saved",saved); counts.put("favorited",favorited); counts.put("ratedHigh",ratedHigh);
        return counts;
    }

    /** 추천 카드에 보여 줄 한 줄. AI가 쓴 말이 아니라 서버가 센 수다. */
    public String text() {
        var parts=new ArrayList<String>();
        if(saved>0) parts.add("일정 저장 "+saved+"명");
        if(favorited>0) parts.add("즐겨찾기 "+favorited+"명");
        if(ratedHigh>0) parts.add("4점 이상 평가 "+ratedHigh+"명");
        return "비슷한 조건으로 여행한 회원 기록 · "+String.join(" · ",parts);
    }
}
