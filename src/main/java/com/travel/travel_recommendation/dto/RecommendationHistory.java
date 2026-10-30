package com.travel.travel_recommendation.dto;

import java.time.*;
import java.util.*;

/** 회원의 추천 이력(SFR-14) 조회용 데이터. 추천 당시 확정한 조건과 저장된 추천 결과를 그대로 보여 준다. */
public final class RecommendationHistory {
    private RecommendationHistory() {}
    /** status: DONE(추천 결과 있음), PENDING(처리 중), NONE(결과 없음·실패·만료). */
    public record Summary(Long id, LocalDateTime requestedAt, String inputMode, String region, String period,
                          List<String> destinations, String status) {}
    public record Condition(String label, String value) {}
    public record Destination(int rank, String name, String region, String reason, String sourceUrl) {}
    /** resultJobId는 아직 만료되지 않은 추천 결과 화면이며, 없으면 다시 볼 수 없다. repeatable은 같은 조건으로 다시 추천할 수 있는지(그때 확정한 조건이 남아 있는지)다. */
    public record Detail(Summary summary, String originalText, String interpretationSummary,
                         List<Condition> conditions, List<Destination> destinations, UUID resultJobId,
                         boolean repeatable) {}
}
