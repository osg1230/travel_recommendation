package com.travel.travel_recommendation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 유료 AI 호출로 이어지는 요청(문장 분석, 여행지 추천 요청·다시 받기)의 횟수를 세어 너무 잦으면 잠시 막는다.
 * 배포하면 누구나 추천을 요청할 수 있어 비용이 한없이 늘지 않게 하려는 것이다. 서버 메모리에만 두며 서버를 다시 켜면 비워진다.
 */
@Component
public class AiRequestLimiter {
    /**
     * 시간당 허용 횟수. 비회원은 IP로만 구분할 수 있고 세션을 새로 열면 다시 셀 수 없어 낮게, 회원은 계정으로 구분해 넉넉하게 둔다.
     * 추천 한 번에 AI를 여러 번(조건 분석·후보 고르기·순위와 이유) 부르므로, 보통 사용에는 충분하고 반복 호출만 막는 정도로 정했다.
     */
    static final int GUEST_LIMIT_PER_HOUR = 10;
    static final int MEMBER_LIMIT_PER_HOUR = 30;
    /** 일정 작업·유료 검색은 초안을 고치다 보면 추천보다 자주 누르므로 따로 세고 조금 넉넉하게 둔다. 한 번에 AI를 2~4번 부른다. */
    static final int GUEST_ITINERARY_LIMIT_PER_HOUR = 20;
    static final int MEMBER_ITINERARY_LIMIT_PER_HOUR = 60;
    static final Duration WINDOW = Duration.ofHours(1);
    /** 기억해 둘 사용자 수의 상한. 가득 차면 ExpiringCache가 비우고 다시 센다. */
    private static final int MAX_USERS = 10_000;

    /** 한 사용자의 1시간 동안 센 횟수. 첫 요청 시각부터 1시간이 지나면 ExpiringCache가 새로 센다. */
    private record Window(Instant start, AtomicInteger count) {}

    private final ExpiringCache<String, Window> windows;
    private final Supplier<Instant> clock;
    /** 테스트에서만 끈다(src/test/resources/config/application.properties). 실제 실행에서는 늘 켜 둔다. */
    @Value("${travel.ai.request-limit.enabled:true}") private boolean enabled = true;

    @Autowired
    public AiRequestLimiter() { this(Instant::now); }

    AiRequestLimiter(Supplier<Instant> clock) {
        this.clock = clock;
        windows = new ExpiringCache<>(WINDOW, MAX_USERS, clock);
    }

    /**
     * 이번 요청을 센다. 한도 안이면 null을, 넘었으면 사용자에게 보여 줄 안내 문장을 돌려준다(넘은 요청은 세지 않는다).
     * 회원은 아이디로, 비회원은 접속 IP로 센다.
     */
    public String tryAcquire(String loginId, String remoteAddress) {
        return acquire("", loginId, remoteAddress, MEMBER_LIMIT_PER_HOUR, GUEST_LIMIT_PER_HOUR);
    }

    /** 일정 초안 작업과 유료 검색(참고 가격·AI 확인·AI 수정 요청)을 센다. 추천과 따로 세며 돌려주는 값은 tryAcquire와 같다. */
    public String tryAcquireItinerary(String loginId, String remoteAddress) {
        return acquire("itinerary:", loginId, remoteAddress, MEMBER_ITINERARY_LIMIT_PER_HOUR, GUEST_ITINERARY_LIMIT_PER_HOUR);
    }

    private String acquire(String prefix, String loginId, String remoteAddress, int memberLimit, int guestLimit) {
        if (!enabled) return null;
        boolean member = loginId != null;
        String key = prefix + (member ? "member:" + loginId : "guest:" + remoteAddress);
        int limit = member ? memberLimit : guestLimit;
        Window window = windows.get(key, () -> new Window(clock.get(), new AtomicInteger()));
        // 동시에 들어온 요청도 한도를 넘지 않게 늘리기 전에 확인한다.
        int used = window.count().getAndUpdate(count -> count < limit ? count + 1 : count);
        if (used < limit) return null;
        long seconds = Duration.between(clock.get(), window.start().plus(WINDOW)).toSeconds();
        long minutes = Math.max(1, (seconds + 59) / 60);
        return "요청이 많아 잠시 뒤 다시 시도해 주세요(약 " + minutes + "분 뒤).";
    }
}
