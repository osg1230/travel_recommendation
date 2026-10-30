package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 여행지(도시)에서 "할 수 있는 것" 목록. 관광공사에 등록된 장소를 새 분류체계 소분류(예: NA040100 국립공원)별로 센 수와
 * 대표 이름 몇 개다. 원하는 여행을 그 여행지에서 실제로 할 수 있는지의 근거로 쓴다(예: 등산 → 속초 국립공원 "설악산 국립공원").
 * 여행지 행(DESTINATION)의 details_json에 30일 보관하며 다른 details_json 정보는 그대로 둔다.
 */
public final class DestinationProfile {
    static final Duration REUSE = Duration.ofDays(30);
    /** 소분류마다 남기는 대표 이름 수. 추천 근거로 보여 줄 만큼만 둔다. */
    static final int NAMES = 5;
    private static final String KEY = "profile";
    private DestinationProfile() {}
    /** 소분류 하나의 장소 수와 대표 이름. */
    public record Category(int count, List<String> names) {}
    /** name은 만들 당시의 여행지 이름이다. 여행지 이름이 바뀌면 예전 목록을 쓰지 않는다. */
    public record Saved(String name, Map<String, Category> categories, Instant checkedAt) {}

    /** 재사용 기간 안에 만든 목록이면 돌려준다. 없거나 읽지 못하면 null이다. */
    public static Map<String, Category> read(Place destination, Instant now, ObjectMapper mapper) {
        if (destination.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(destination.getDetailsJson()).path(KEY);
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Saved.class);
            if (saved.categories() == null || saved.checkedAt() == null || !destination.getName().equals(saved.name())) return null;
            if (saved.checkedAt().isAfter(now) || !saved.checkedAt().isAfter(now.minus(REUSE))) return null;
            return Map.copyOf(saved.categories());
        } catch (RuntimeException error) { return null; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(목록 보관은 건너뛴다). */
    public static String write(Place destination, Map<String, Category> categories, Instant now, ObjectMapper mapper) {
        try {
            var node = destination.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(destination.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            root.set(KEY, mapper.valueToTree(new Saved(destination.getName(), new TreeMap<>(categories), now)));
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }

    /** 장소 목록을 소분류별 수와 대표 이름으로 묶는다. 같은 소분류에 같은 이름은 한 번만 센다(여러 구 조회에 같은 곳이 나와도). */
    public static Map<String, Category> summarize(List<TourApiClient.ClassifiedPlace> places) {
        Map<String, Set<String>> byCategory = new TreeMap<>();
        for (var place : places) byCategory.computeIfAbsent(place.category(), key -> new LinkedHashSet<>()).add(place.name());
        Map<String, Category> result = new TreeMap<>();
        byCategory.forEach((code, names) -> result.put(code, new Category(names.size(), names.stream().limit(NAMES).toList())));
        return result;
    }

    /** codes 소분류들에 든 장소 수의 합. */
    public static int count(Map<String, Category> profile, Collection<String> codes) {
        return codes.stream().mapToInt(code -> profile.getOrDefault(code, new Category(0, List.of())).count()).sum();
    }

    /** codes 소분류들의 대표 이름을 앞에서부터 limit개. */
    public static List<String> names(Map<String, Category> profile, Collection<String> codes, int limit) {
        return codes.stream().flatMap(code -> profile.getOrDefault(code, new Category(0, List.of())).names().stream())
            .distinct().limit(limit).toList();
    }
}
