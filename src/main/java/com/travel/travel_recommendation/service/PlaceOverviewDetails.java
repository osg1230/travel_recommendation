package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 관광공사(TourAPI)에서 찾은 장소 소개문을 details_json에 보관해, 같은 장소를 다시 조회하지 않게 한다(2026-10-31).
 * details_json의 다른 정보(운영정보·사진·참고 가격)는 그대로 둔다. 찾지 못한 결과도 기록해 반복 호출을 막는다.
 */
public final class PlaceOverviewDetails {
    /** 소개문은 거의 바뀌지 않아 30일, 찾지 못한 기록은 관광공사에 새로 등록될 수 있어 7일 동안 다시 조회하지 않는다. */
    static final Duration FOUND_REUSE = Duration.ofDays(30), NOT_FOUND_REUSE = Duration.ofDays(7);
    private static final String KEY = "tourApiOverview";
    private PlaceOverviewDetails() {}
    /** text가 비어 있으면 찾지 못한 기록이다. contentId는 연결한 관광공사 장소 ID다. */
    public record Overview(String contentId, String text, Instant checkedAt) {
        public boolean found() { return text != null && !text.isBlank(); }
    }
    public record Snapshot(String name, String address, Overview overview) {}

    /** 같은 이름·주소로 찾은 기록이 재사용 기간 안이면 돌려준다. 없거나 읽지 못하면 null이다. */
    public static Overview read(Place place, Instant now, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path(KEY);
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Snapshot.class);
            var overview = saved.overview();
            // 장소 이름·주소가 바뀌면 예전에 연결한 소개문을 쓰지 않는다.
            if (overview == null || overview.checkedAt() == null
                    || !place.getName().equals(saved.name()) || !java.util.Objects.equals(place.getAddress(), saved.address())) return null;
            var reuse = overview.found() ? FOUND_REUSE : NOT_FOUND_REUSE;
            if (overview.checkedAt().isAfter(now) || !overview.checkedAt().isAfter(now.minus(reuse))) return null;
            return overview;
        } catch (RuntimeException error) { return null; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(소개문 보관은 건너뛴다). */
    public static String write(Place place, Overview overview, ObjectMapper mapper) {
        try {
            var node = place.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(place.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            root.set(KEY, mapper.valueToTree(new Snapshot(place.getName(), place.getAddress(), overview)));
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }
}
