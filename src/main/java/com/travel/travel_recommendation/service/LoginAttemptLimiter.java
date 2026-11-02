package com.travel.travel_recommendation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 비밀번호를 계속 맞혀 보는 로그인과 계정을 대량으로 만드는 가입을 막는다. AiRequestLimiter처럼 서버 메모리에서만 센다.
 * 로그인은 비밀번호를 확인하기 전에 먼저 센다. 실패한 뒤에 세면 동시에 보낸 요청이 모두 통과하기 때문이다.
 */
public class LoginAttemptLimiter {
    static final int LOGIN_ID_LIMIT = 5;
    static final Duration LOGIN_ID_WINDOW = Duration.ofMinutes(15);
    /** 학교·회사처럼 한 IP를 여러 사람이 함께 쓰는 곳을 생각해 아이디 한도보다 넉넉하게 둔다. */
    static final int ADDRESS_LIMIT = 30;
    static final Duration ADDRESS_WINDOW = Duration.ofHours(1);
    /** 출발지를 잘못 적어 다시 내는 경우가 있어 가입 시도 5번까지 허용한다. 중복 아이디·닉네임으로 끝난 시도는 세지 않는다. */
    static final int SIGNUP_LIMIT = 5;
    static final Duration SIGNUP_WINDOW = Duration.ofHours(1);
    private static final int MAX_ENTRIES = 10_000;

    // 임의 아이디를 쏟아내 캐시가 비워져도 IP 횟수는 남도록 따로 둔다.
    private final ExpiringCache<String, AtomicInteger> loginIds;
    private final ExpiringCache<String, AtomicInteger> addresses;
    private final ExpiringCache<String, AtomicInteger> signups;
    private final boolean enabled;

    public LoginAttemptLimiter(boolean enabled) { this(enabled, Instant::now); }

    LoginAttemptLimiter(boolean enabled, Supplier<Instant> clock) {
        this.enabled = enabled;
        loginIds = new ExpiringCache<>(LOGIN_ID_WINDOW, MAX_ENTRIES, clock);
        addresses = new ExpiringCache<>(ADDRESS_WINDOW, MAX_ENTRIES, clock);
        signups = new ExpiringCache<>(SIGNUP_WINDOW, MAX_ENTRIES, clock);
    }

    /** 이번 로그인 시도를 센다. 한도를 넘었으면 false다. 없는 아이디도 똑같이 세어 아이디가 있는지 드러나지 않는다. */
    public boolean tryLogin(String loginId, String remoteAddress) {
        if (!enabled) return true;
        var address = addresses.get(remoteAddress, AtomicInteger::new);
        if (!acquire(address, ADDRESS_LIMIT)) return false;
        if (acquire(loginIds.get(key(loginId), AtomicInteger::new), LOGIN_ID_LIMIT)) return true;
        address.decrementAndGet();
        return false;
    }

    /** 로그인에 성공하면 그 아이디의 횟수를 비운다. 맞게 들어온 시도는 IP 횟수에서도 뺀다. */
    public void loginSucceeded(String loginId, String remoteAddress) {
        if (!enabled) return;
        loginIds.remove(key(loginId));
        release(addresses.peek(remoteAddress));
    }

    /** DB 장애처럼 비밀번호를 확인하지 못한 시도는 사용자 잘못이 아니므로 횟수에서 뺀다. */
    public void loginNotChecked(String loginId, String remoteAddress) {
        if (!enabled) return;
        release(loginIds.peek(key(loginId)));
        release(addresses.peek(remoteAddress));
    }

    /** 가입 시도를 센다. 한도 안이면 null을, 넘었으면 화면에 보여 줄 안내 문장을 돌려준다(AiRequestLimiter와 같은 방식). */
    public String tryAcquireSignup(String remoteAddress) {
        if (!enabled || acquire(signups.get(remoteAddress, AtomicInteger::new), SIGNUP_LIMIT)) return null;
        return "가입 요청이 많아요. 1시간쯤 뒤에 다시 시도해 주세요.";
    }

    // 동시에 들어온 요청도 한도를 넘지 않게 늘리기 전에 확인한다.
    private static boolean acquire(AtomicInteger count, int limit) {
        return count.getAndUpdate(used -> used < limit ? used + 1 : used) < limit;
    }

    private static void release(AtomicInteger count) {
        if (count != null) count.updateAndGet(used -> Math.max(0, used - 1));
    }

    // DB 비교가 대소문자를 구분하지 않아 Abc와 abc는 같은 계정이다. 대소문자를 바꿔 한도를 피하지 못하게 맞춘다.
    private static String key(String loginId) { return loginId.strip().toLowerCase(Locale.ROOT); }
}
