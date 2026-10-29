package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 여행지(도시)의 대표 관광지 이름 목록(관광공사 인기 순서 + GPT 제안)을 여행지 행(DESTINATION)의 details_json에 보관한다.
 * 연관 관광지는 한 달 단위 자료이고 대표 관광지도 자주 바뀌지 않아 30일 동안 다시 계산하지 않는다. 연관 관광지를 받지 못한
 * 목록(관광공사 오류 등)은 하루만 쓴다. 이름은 카카오 검색어로만 쓰고 화면에 그대로 보이지 않는다. 다른 details_json 정보는 그대로 둔다.
 */
public final class DestinationLandmarks {
    static final Duration REUSE = Duration.ofDays(30), RETRY = Duration.ofDays(1);
    private static final String KEY = "landmarks";
    private DestinationLandmarks() {}
    /** name은 만들 당시의 여행지 이름이다. 여행지 이름이 바뀌면 예전 목록을 쓰지 않는다. popular는 연관 관광지를 받았는지다. */
    public record Saved(String name, List<String> names, boolean popular, Instant checkedAt) {}

    /** 재사용 기간 안에 만든 목록이면 돌려준다. 없거나 읽지 못하면 null이다. */
    public static List<String> read(Place destination, Instant now, ObjectMapper mapper) {
        if (destination.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(destination.getDetailsJson()).path(KEY);
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Saved.class);
            if (saved.names() == null || saved.checkedAt() == null || !destination.getName().equals(saved.name())) return null;
            var reuse = saved.popular() ? REUSE : RETRY;
            if (saved.checkedAt().isAfter(now) || !saved.checkedAt().isAfter(now.minus(reuse))) return null;
            return List.copyOf(saved.names());
        } catch (RuntimeException error) { return null; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(목록 보관은 건너뛴다). */
    public static String write(Place destination, List<String> names, boolean popular, Instant now, ObjectMapper mapper) {
        try {
            var node = destination.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(destination.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            root.set(KEY, mapper.valueToTree(new Saved(destination.getName(), List.copyOf(names), popular, now)));
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }
}
