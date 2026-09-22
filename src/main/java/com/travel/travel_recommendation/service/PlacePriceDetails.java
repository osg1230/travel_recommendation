package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.PriceQuote;
import com.travel.travel_recommendation.entity.Place;
import java.time.Duration;
import java.time.Instant;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * AI 웹 검색으로 찾은 참고 가격을 장소의 details_json에 보관해, 다른 일정에서 같은 숙소·음식점을 다시 검색하지 않게 한다.
 * details_json의 다른 정보(관광공사 운영정보 등)는 그대로 둔다. 가격 검색은 유료라 재사용 기간을 길게 두고,
 * 찾지 못한 결과는 새 출처가 생길 수 있어 짧게 둔다.
 */
public final class PlacePriceDetails {
    static final Duration FOUND_REUSE = Duration.ofDays(7), NOT_FOUND_REUSE = Duration.ofHours(24);
    private static final String KEY = "referencePrice";
    private PlacePriceDetails() {}
    public record Snapshot(String name, String address, PriceQuote quote) {}

    /** 같은 이름·주소·종류로 찾은 결과가 재사용 기간 안이면 돌려준다. 없거나 읽지 못하면 null이다. */
    public static PriceQuote read(Place place, String kind, Instant now, ObjectMapper mapper) {
        if (place.getDetailsJson() == null) return null;
        try {
            var node = mapper.readTree(place.getDetailsJson()).path(KEY);
            if (!node.isObject()) return null;
            var saved = mapper.treeToValue(node, Snapshot.class);
            var quote = saved.quote();
            // 장소 이름·주소가 바뀌면 과거 가격을 쓰지 않는다.
            if (quote == null || quote.checkedAt() == null || !kind.equals(quote.kind())
                    || !place.getName().equals(saved.name()) || !place.getAddress().equals(saved.address())) return null;
            var reuse = quote.found() ? FOUND_REUSE : NOT_FOUND_REUSE;
            if (quote.checkedAt().isAfter(now) || !quote.checkedAt().isAfter(now.minus(reuse))) return null;
            return quote;
        } catch (RuntimeException error) { return null; }
    }

    /** 기존 details_json을 읽지 못하면 덮어쓰지 않고 null을 돌려준다(가격 보관은 건너뛴다). */
    public static String write(Place place, PriceQuote quote, ObjectMapper mapper) {
        try {
            var node = place.getDetailsJson() == null ? mapper.createObjectNode() : mapper.readTree(place.getDetailsJson());
            if (!(node instanceof ObjectNode root)) return null;
            root.set(KEY, mapper.valueToTree(new Snapshot(place.getName(), place.getAddress(), quote)));
            return mapper.writeValueAsString(root);
        } catch (RuntimeException error) { return null; }
    }
}
