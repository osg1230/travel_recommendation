package com.travel.travel_recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class LoginAttemptLimiterTests {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-02T00:00:00Z"));
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(true, now::get);

    private void pass(java.time.Duration time) { now.set(now.get().plus(time)); }

    @Test void sameLoginIdIsBlockedAfterFiveTriesEvenFromAnotherAddressOrCase() {
        for (int i = 0; i < LoginAttemptLimiter.LOGIN_ID_LIMIT; i++) assertThat(limiter.tryLogin("traveler", "1.1.1." + i)).isTrue();
        assertThat(limiter.tryLogin(" TRAVELER ", "2.2.2.2")).isFalse();
        pass(LoginAttemptLimiter.LOGIN_ID_WINDOW);
        assertThat(limiter.tryLogin("traveler", "2.2.2.2")).isTrue();
    }

    @Test void oneAddressIsBlockedAcrossManyLoginIds() {
        for (int i = 0; i < LoginAttemptLimiter.ADDRESS_LIMIT; i++) assertThat(limiter.tryLogin("id_" + i, "1.1.1.1")).isTrue();
        assertThat(limiter.tryLogin("another", "1.1.1.1")).isFalse();
        assertThat(limiter.tryLogin("another", "2.2.2.2")).isTrue();
    }

    @Test void successClearsLoginIdAndDoesNotUseUpTheAddress() {
        for (int i = 0; i < LoginAttemptLimiter.LOGIN_ID_LIMIT; i++) limiter.tryLogin("traveler", "1.1.1.1");
        limiter.loginSucceeded("traveler", "1.1.1.1");
        for (int i = 0; i < LoginAttemptLimiter.LOGIN_ID_LIMIT; i++) assertThat(limiter.tryLogin("traveler", "1.1.1.1")).isTrue();
        // 여러 사람이 한 IP로 정상 로그인해도 쌓이지 않는다.
        for (int i = 0; i < LoginAttemptLimiter.ADDRESS_LIMIT * 2; i++) {
            assertThat(limiter.tryLogin("member" + i, "3.3.3.3")).isTrue();
            limiter.loginSucceeded("member" + i, "3.3.3.3");
        }
    }

    // 아이디 한도로 막힌 시도는 비밀번호를 확인하지 않았으므로 IP 횟수를 쓰지 않는다.
    @Test void attemptBlockedByLoginIdDoesNotUseUpTheAddress() {
        for (int i = 0; i < LoginAttemptLimiter.LOGIN_ID_LIMIT; i++) limiter.tryLogin("traveler", "9.9.9." + i);
        for (int i = 0; i < 100; i++) assertThat(limiter.tryLogin("traveler", "1.1.1.1")).isFalse();
        assertThat(limiter.tryLogin("someone", "1.1.1.1")).isTrue();
    }

    @Test void databaseFailureIsNotCounted() {
        for (int i = 0; i < LoginAttemptLimiter.LOGIN_ID_LIMIT * 2; i++) {
            assertThat(limiter.tryLogin("traveler", "1.1.1.1")).isTrue();
            limiter.loginNotChecked("traveler", "1.1.1.1");
        }
    }

    @Test void signupIsLimitedPerAddress() {
        for (int i = 0; i < LoginAttemptLimiter.SIGNUP_LIMIT; i++) assertThat(limiter.tryAcquireSignup("1.1.1.1")).isNull();
        assertThat(limiter.tryAcquireSignup("1.1.1.1")).contains("가입 요청이 많아요");
        assertThat(limiter.tryAcquireSignup("2.2.2.2")).isNull();
        pass(LoginAttemptLimiter.SIGNUP_WINDOW);
        assertThat(limiter.tryAcquireSignup("1.1.1.1")).isNull();
    }

    @Test void turnedOffLimiterAllowsEverything() {
        var off = new LoginAttemptLimiter(false);
        for (int i = 0; i < 100; i++) {
            assertThat(off.tryLogin("traveler", "1.1.1.1")).isTrue();
            assertThat(off.tryAcquireSignup("1.1.1.1")).isNull();
        }
    }
}
