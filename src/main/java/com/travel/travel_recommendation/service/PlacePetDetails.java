package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlacePetInfo;
import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/** 장소 details_json의 다른 정보는 보존하고 반려동물 동반 정보(tourApiPet)만 읽고 쓴다. 운영정보(PlaceOperatingDetails)와 같은 방식이다. */
public final class PlacePetDetails {
    private PlacePetDetails() {}
    /** 동반 정책은 자주 바뀌지 않으므로 7일 동안 다시 조회하지 않는다. */
    static final Duration REUSE = Duration.ofDays(7);
    public record Snapshot(String name, String address, PlacePetInfo info) {}
    /** 관광공사에서 장소를 하나로 찾지 못한 기록(tourApiPetMiss). 소개문·사진의 '못 찾음'처럼 7일 동안 다시 찾지 않는다. */
    public record Miss(String name, String address, Instant missedAt) {}

    public static PlacePetInfo read(Place place, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path("tourApiPet");
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Snapshot.class);
            // 장소 이름·주소가 바뀌면 과거에 연결한 정보를 재사용하지 않는다.
            return place.getName().equals(saved.name()) && place.getAddress().equals(saved.address()) ? saved.info() : null;
        } catch (RuntimeException error) { return null; }
    }

    public static boolean fresh(PlacePetInfo info, Instant now) {
        return info != null && info.fetchedAt() != null && !info.fetchedAt().isAfter(now) && info.fetchedAt().isAfter(now.minus(REUSE));
    }

    public static boolean missedRecently(Place place, Instant now, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return false;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path("tourApiPetMiss");
            if (!node.isObject()) return false;
            var miss = mapper.treeToValue(node, Miss.class);
            return place.getName().equals(miss.name()) && java.util.Objects.equals(place.getAddress(), miss.address())
                && miss.missedAt() != null && !miss.missedAt().isAfter(now) && miss.missedAt().isAfter(now.minus(REUSE));
        } catch (RuntimeException error) { return false; }
    }

    public static String writeMiss(Place place, Instant now, ObjectMapper mapper) {
        var node = place.getDetailsJson() == null ? mapper.createObjectNode() : parse(place.getDetailsJson(), mapper);
        if (!(node instanceof ObjectNode root)) throw invalid();
        root.set("tourApiPetMiss", mapper.valueToTree(new Miss(place.getName(), place.getAddress(), now)));
        try {
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { throw invalid(); }
    }

    public static String write(Place place, PlacePetInfo info, ObjectMapper mapper) {
        var node = place.getDetailsJson() == null ? mapper.createObjectNode() : parse(place.getDetailsJson(), mapper);
        if (!(node instanceof ObjectNode root)) throw invalid();
        root.set("tourApiPet", mapper.valueToTree(new Snapshot(place.getName(), place.getAddress(), info)));
        try {
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { throw invalid(); }
    }

    private static tools.jackson.databind.JsonNode parse(String json, ObjectMapper mapper) {
        try { return mapper.readTree(json); } catch (RuntimeException error) { throw invalid(); }
    }
    private static RecommendationException invalid() {
        return new RecommendationException("TOUR_DETAILS", "기존 장소 상세정보를 확인해야 해요.");
    }
}
