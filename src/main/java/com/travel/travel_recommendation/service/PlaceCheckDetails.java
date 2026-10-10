package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.CheckAnswer;
import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 웹 검색으로 찾은 확인 결과(반려동물 동반·여행 목적 활동)를 장소의 details_json에 주제별로 보관해, 다른 초안에서 같은 질문을
 * 다시 검색하지 않게 한다. 참고 가격(PlacePriceDetails)과 같은 방식이며 다른 상세정보는 그대로 둔다.
 * 목적 활동은 질문 문장이 같을 때만 재사용한다(목적이 다르면 다른 질문이다).
 */
public final class PlaceCheckDetails {
    static final Duration FOUND_REUSE = Duration.ofDays(7), NOT_FOUND_REUSE = Duration.ofHours(24);
    private static final String KEY = "aiChecks";
    private PlaceCheckDetails() {}
    public record Snapshot(String name, String address, CheckAnswer answer) {}

    /** 같은 이름·주소·주제·질문으로 찾은 결과가 재사용 기간 안이면 돌려준다. 없거나 읽지 못하면 null이다. */
    public static CheckAnswer read(Place place, String topic, String question, Instant now, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path(KEY).path(topic);
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Snapshot.class);
            var answer = saved.answer();
            if (answer == null || answer.checkedAt() == null || !question.equals(answer.question())
                    || !place.getName().equals(saved.name()) || !place.getAddress().equals(saved.address())) return null;
            var reuse = answer.found() ? FOUND_REUSE : NOT_FOUND_REUSE;
            if (answer.checkedAt().isAfter(now) || !answer.checkedAt().isAfter(now.minus(reuse))) return null;
            return answer;
        } catch (RuntimeException error) { return null; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(보관은 건너뛴다). */
    public static String write(Place place, CheckAnswer answer, ObjectMapper mapper) {
        try {
            var node = place.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(place.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            var checks = root.path(KEY) instanceof ObjectNode existing ? existing : root.putObject(KEY);
            checks.set(answer.topic(), mapper.valueToTree(new Snapshot(place.getName(), place.getAddress(), answer)));
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }
}
