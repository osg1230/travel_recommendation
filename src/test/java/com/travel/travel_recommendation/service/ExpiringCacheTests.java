package com.travel.travel_recommendation.service;

import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ExpiringCacheTests {
    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-09-22T00:00:00Z"));
    private final AtomicInteger loads = new AtomicInteger();

    @Test void reusesValueUntilItExpires() {
        var cache = new ExpiringCache<String, Integer>(Duration.ofHours(6), 10, now::get);
        assertThat(cache.get("갯벌", loads::incrementAndGet)).isEqualTo(1);
        now.set(now.get().plus(Duration.ofHours(5)));
        assertThat(cache.get("갯벌", loads::incrementAndGet)).isEqualTo(1);
        now.set(now.get().plus(Duration.ofHours(1)));
        assertThat(cache.get("갯벌", loads::incrementAndGet)).isEqualTo(2);
        cache.clear();
        assertThat(cache.get("갯벌", loads::incrementAndGet)).isEqualTo(3);
    }

    @Test void failedLoadIsNotKeptAndSizeStaysBounded() {
        var cache = new ExpiringCache<String, Integer>(Duration.ofHours(6), 2, now::get);
        assertThatThrownBy(() -> cache.get("a", () -> { throw new RecommendationException("TOUR_QUOTA", "한도"); }))
            .isInstanceOf(RecommendationException.class);
        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(1);
        cache.get("b", loads::incrementAndGet);
        // 가득 차면 비우고 새로 쌓으므로 먼저 넣은 값은 다시 불러온다.
        cache.get("c", loads::incrementAndGet);
        assertThat(cache.get("a", loads::incrementAndGet)).isEqualTo(4);
    }
}
