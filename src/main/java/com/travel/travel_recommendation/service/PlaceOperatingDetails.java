package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import com.travel.travel_recommendation.entity.Place;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 기존 details_json의 다른 정보는 보존하고 운영정보만 읽고 쓴다. */
public final class PlaceOperatingDetails {
    private PlaceOperatingDetails() {}
    public record Snapshot(String name, String address, PlaceOperatingInfo info) {}

    public static PlaceOperatingInfo read(Place place, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path("tourApiOperating");
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Snapshot.class);
            // 장소 이름·주소가 바뀌면 과거에 연결한 운영정보를 재사용하지 않는다.
            return place.getName().equals(saved.name()) && place.getAddress().equals(saved.address()) ? saved.info() : null;
        } catch (RuntimeException error) { return null; }
    }

    public static String write(Place place, PlaceOperatingInfo info, ObjectMapper mapper) {
        var node = place.getDetailsJson() == null ? mapper.createObjectNode() : parse(place.getDetailsJson(), mapper);
        if (!(node instanceof ObjectNode root)) throw invalid();
        root.set("tourApiOperating", mapper.valueToTree(new Snapshot(place.getName(), place.getAddress(), info)));
        try {
            return mapper.writeValueAsString(root);
        // 기존 상세정보를 읽거나 다시 쓰지 못하면 덮어쓰지 않고 안내만 한다.
        } catch (RuntimeException error) { throw invalid(); }
    }

    private static tools.jackson.databind.JsonNode parse(String json, ObjectMapper mapper) {
        try { return mapper.readTree(json); } catch (RuntimeException error) { throw invalid(); }
    }
    private static RecommendationException invalid() {
        return new RecommendationException("TOUR_DETAILS", "기존 장소 상세정보를 확인해야 해요.");
    }
}
