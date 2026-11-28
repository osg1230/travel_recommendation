package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 기존 details_json의 다른 정보는 보존하고 인허가 자료로 확인한 영업 상태(licenseStatus)만 읽고 쓴다. */
public final class PlaceLicenseDetails {
    private PlaceLicenseDetails() {}
    /** 확인 결과를 다시 조회하지 않고 쓰는 기간. 인허가 자료는 매일 갱신되지만 호출을 아끼려고 일주일 쓴다. */
    static final Duration RECHECK_AFTER = Duration.ofDays(7);
    /** 폐업 확인을 믿는 기간. 지나면 후보로 돌아와 다시 조회한다. */
    static final Duration CLOSED_VALID = Duration.ofDays(30);

    /** closed는 폐업 기록만 있고 영업 중인 기록이 없다는 뜻이다. 기록을 찾지 못한 곳도 closed=false로 남겨 다시 조회하지 않는다. */
    public record Status(String name, String address, boolean closed, String closedOn, Instant checkedAt) {}

    public static Status read(Place place, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path("licenseStatus");
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Status.class);
            // 장소 이름·주소가 바뀌면 과거에 확인한 결과를 재사용하지 않는다.
            return saved.checkedAt() != null && place.getName().equals(saved.name()) && place.getAddress().equals(saved.address()) ? saved : null;
        } catch (RuntimeException error) { return null; }
    }

    /** 인허가 자료에서 폐업으로 확인한 지 CLOSED_VALID가 지나지 않은 장소인지. 이런 장소는 일정 후보에서 뺀다(PlaceCandidateService.usable). */
    public static boolean closed(Place place, ObjectMapper mapper, Instant now) {
        var status = read(place, mapper);
        return status != null && status.closed() && status.checkedAt().isAfter(now.minus(CLOSED_VALID));
    }

    /** RECHECK_AFTER 안에 확인한 적이 있어 다시 조회하지 않아도 되는지. */
    public static boolean checkedRecently(Place place, ObjectMapper mapper, Instant now) {
        var status = read(place, mapper);
        return status != null && status.checkedAt().isAfter(now.minus(RECHECK_AFTER));
    }

    public static String write(Place place, boolean closed, String closedOn, Instant checkedAt, ObjectMapper mapper) {
        try {
            var node = place.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(place.getDetailsJson());
            if (!(node instanceof ObjectNode root)) throw invalid();
            root.set("licenseStatus", mapper.valueToTree(new Status(place.getName(), place.getAddress(), closed, closedOn, checkedAt)));
            return mapper.writeValueAsString(root);
        // 기존 상세정보를 읽거나 다시 쓰지 못하면 덮어쓰지 않는다.
        } catch (RecommendationException error) { throw error;
        } catch (RuntimeException error) { throw invalid(); }
    }

    private static RecommendationException invalid() {
        return new RecommendationException("LICENSE_DETAILS", "기존 장소 상세정보를 확인해야 해요.");
    }
}
