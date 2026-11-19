package com.travel.travel_recommendation.service;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ItineraryJobDeadlineTests {
    // 보조 단계는 자기 제한(예: 40초)과 '작업 마감 − AI 안내 시간(60초)' 중 이른 쪽에서 끝나, 단계 시간의 합이 처리 제한을 넘지 않는다.
    @Test void stepStopsBeforeJobDeadlineLeavesTimeForAssistant() {
        var start = Instant.now();
        // 작업 마감이 100초 남았으면 40초짜리 단계는 자기 제한(40초)을 쓴다.
        var early = ItineraryJobService.stepUntil(start.plusSeconds(100), 40);
        assertThat(Duration.between(start, early).toSeconds()).isBetween(39L, 41L);
        // 앞 단계에서 시간을 써서 마감이 70초 남았으면 40초 대신 10초(70 − 60)에서 끝낸다.
        var deadline = start.plusSeconds(70);
        assertThat(ItineraryJobService.stepUntil(deadline, 40)).isEqualTo(deadline.minusSeconds(ItineraryJobService.ASSIST_RESERVE_SECONDS));
        // 남은 시간이 60초보다 적으면 이미 지난 시각이라 단계를 바로 건너뛴다.
        assertThat(ItineraryJobService.stepUntil(start.plusSeconds(30), 25)).isBefore(Instant.now());
    }
}
