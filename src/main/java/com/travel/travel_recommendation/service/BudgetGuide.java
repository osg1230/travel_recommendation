package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 예산을 입력하지 않았을 때의 안내(SFR-03-03). 가격 자료가 없으므로 금액을 지어내지 않고
 * ① 공식 통계의 전국 평균 지출액을 참고값으로, ② 확인된 숙소를 유형별 예산대로 나눠 보여 준다.
 */
public final class BudgetGuide {
    private BudgetGuide() {}

    /**
     * 2025 국민여행조사(한국문화관광연구원, 문화체육관광부 2026-06-30 발표): 여행 경험자의 국내여행 1회 평균 지출액.
     * 1인이 여행 1회에 쓴 전국 평균이며 특정 지역·일정의 비용이 아니다. 여행 일수를 곱해 추정하지 않는다.
     */
    public record Spending(int dayTripWon, int overnightWon, String surveyName, String sourceName, String sourceUrl) {}
    public static final Spending NATIONAL_AVERAGE=new Spending(69_000,221_000,"2025 국민여행조사",
        "문화체육관광부 보도자료(2026-06-30)","https://www.mcst.go.kr/site/s_notice/press/pressView.jsp?pSeq=22557");

    /**
     * 숙소 유형으로 나눈 예산대. 요금이 아니라 유형 기준이라 같은 유형 안에서도 요금 차이가 크다.
     * 호텔은 등급 정보가 없어 모두 '보통'으로 둔다.
     */
    public enum StayTier {
        BUDGET("절약형","캠핑장·게스트하우스·모텔처럼 비교적 요금이 낮은 편인 유형"),
        STANDARD("보통","호텔·펜션처럼 일반적인 유형"),
        COMFORT("여유형","리조트·콘도처럼 부대시설이 많은 편인 유형");
        private final String label, description;
        StayTier(String label, String description) { this.label=label; this.description=description; }
        public String getLabel() { return label; }
        public String getDescription() { return description; }

        /** 카카오 분류(예: "여행 > 숙박 > 야영,캠핑장")나 시스템 유형·이름의 낱말로 나눈다. 판단할 낱말이 없으면 빈 값이다. */
        public static Optional<StayTier> of(String subtype, String name) {
            String text=((subtype==null?"":subtype)+" "+(name==null?"":name));
            if(text.matches(".*(캠핑|야영|글램핑|게스트하우스|호스텔|민박|모텔).*")) return Optional.of(BUDGET);
            if(text.matches(".*(리조트|콘도).*")) return Optional.of(COMFORT);
            if(text.matches(".*(호텔|펜션|스테이).*")) return Optional.of(STANDARD);
            return Optional.empty();
        }
    }
}
