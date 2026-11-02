package com.travel.travel_recommendation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

/** 유료 AI 요청 횟수 제한. 시계를 직접 움직여 1시간 단위로 세는지 확인한다. */
class AiRequestLimiterTests {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-31T01:00:00Z"));
    private final AiRequestLimiter limiter = new AiRequestLimiter(now::get);

    @Test void guestsAreLimitedByAddressAndToldWhenToRetry() {
        for (int i = 0; i < AiRequestLimiter.GUEST_LIMIT_PER_HOUR; i++) assertThat(limiter.tryAcquire(null, "10.0.0.1")).isNull();
        now.set(now.get().plus(Duration.ofMinutes(20)));
        // 첫 요청에서 20분이 지났으므로 약 40분 뒤에 다시 셀 수 있다.
        assertThat(limiter.tryAcquire(null, "10.0.0.1")).isEqualTo("요청이 많아 잠시 뒤 다시 시도해 주세요(약 40분 뒤).");
        // 다른 IP는 따로 센다.
        assertThat(limiter.tryAcquire(null, "10.0.0.2")).isNull();
        // 1시간이 지나면 새로 센다.
        now.set(now.get().plus(Duration.ofMinutes(41)));
        assertThat(limiter.tryAcquire(null, "10.0.0.1")).isNull();
    }

    @Test void membersHaveLargerLimitPerAccount() {
        for (int i = 0; i < AiRequestLimiter.MEMBER_LIMIT_PER_HOUR; i++) assertThat(limiter.tryAcquire("member1", "10.0.0.1")).isNull();
        assertThat(limiter.tryAcquire("member1", "10.0.0.9")).startsWith("요청이 많아 잠시 뒤 다시 시도해 주세요");
        // 같은 IP라도 다른 회원이나 비회원은 따로 센다.
        assertThat(limiter.tryAcquire("member2", "10.0.0.1")).isNull();
        assertThat(limiter.tryAcquire(null, "10.0.0.1")).isNull();
        // 남은 시간이 1분이 안 되어도 "약 1분 뒤"로 안내한다.
        now.set(now.get().plus(Duration.ofMinutes(59)).plusSeconds(30));
        assertThat(limiter.tryAcquire("member1", "10.0.0.1")).isEqualTo("요청이 많아 잠시 뒤 다시 시도해 주세요(약 1분 뒤).");
    }

    // 일정 작업은 추천과 따로 세어, 초안을 고치느라 많이 눌러도 추천 요청 횟수는 줄지 않는다.
    @Test void itineraryWorkIsCountedApartFromRecommendations() {
        for (int i = 0; i < AiRequestLimiter.GUEST_ITINERARY_LIMIT_PER_HOUR; i++) assertThat(limiter.tryAcquireItinerary(null, "10.0.0.1")).isNull();
        assertThat(limiter.tryAcquireItinerary(null, "10.0.0.1")).startsWith("요청이 많아 잠시 뒤 다시 시도해 주세요");
        assertThat(limiter.tryAcquire(null, "10.0.0.1")).isNull();
        assertThat(limiter.tryAcquireItinerary("member1", "10.0.0.1")).isNull();
        // 1시간이 지나면 다시 셀 수 있다.
        now.set(now.get().plus(AiRequestLimiter.WINDOW));
        assertThat(limiter.tryAcquireItinerary(null, "10.0.0.1")).isNull();
    }
}
