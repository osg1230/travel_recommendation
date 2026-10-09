package com.travel.travel_recommendation.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 체험용 객실 종류. 실제 호텔의 객실을 가져온 것이 아니라 흔히 쓰는 객실 이름만 빌려 쓴다.
 * 가격은 숙소 기준가(스탠다드 더블 가격)에 종류별 배수를 곱해 정한다. DB에는 이름(SINGLE 등)을 저장한다(SQL 016).
 */
public enum RoomType {
    SINGLE("싱글룸", 1, "0.8"),
    STANDARD_DOUBLE("스탠다드 더블", 2, "1.0"),
    STANDARD_TWIN("스탠다드 트윈", 2, "1.0"),
    DELUXE_DOUBLE("디럭스 더블", 2, "1.3"),
    FAMILY("패밀리룸", 4, "1.6"),
    SUITE("스위트룸", 2, "2.0");

    private final String label;
    private final int maxPeople;
    private final BigDecimal priceRate;

    RoomType(String label, int maxPeople, String priceRate) {
        this.label = label; this.maxPeople = maxPeople; this.priceRate = new BigDecimal(priceRate);
    }

    public String label() { return label; }
    public int maxPeople() { return maxPeople; }

    /** 기준가 × 배수를 천 원 단위로 반올림한 1실 1박 가격. */
    public BigDecimal price(BigDecimal basePrice) {
        return basePrice.multiply(priceRate).divide(BigDecimal.valueOf(1000), 0, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(1000));
    }

    /** DB 값으로 찾는다. 객실 종류가 없던 예전 행(NULL)은 null을 돌려준다. */
    public static RoomType of(String code) {
        return code == null ? null : valueOf(code);
    }
}
