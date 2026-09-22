package com.travel.travel_recommendation.service;

import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * 테스트 결제수단(TEST). 외부 결제사나 금융정보 없이, 화면에서 고른 결과대로 승인 또는 거절만 돌려준다.
 * 결제 처리 중 예외(SFR-16-03·TER-08)는 테스트에서 이 빈을 바꿔 재현한다.
 */
@Component
public class VirtualPaymentGateway {
    public static final String METHOD = "TEST";
    public static final String DECLINED = "TEST_DECLINED";

    public record Outcome(boolean approved, String failureCode) {}

    public Outcome charge(long reservationId, BigDecimal amount, boolean simulateDecline) {
        return simulateDecline ? new Outcome(false, DECLINED) : new Outcome(true, null);
    }
}
