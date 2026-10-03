package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 실제 OpenAI 웹 검색 호출(유료). RUN_OPENAI_PRICE_TESTS=true일 때만 실행하고 키와 전체 응답은 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_PRICE_TESTS", matches = "true")
class OpenAiPriceLiveTests {
    private static PlaceCandidate place(long id, String name, String address) {
        return new PlaceCandidate(id, name, address, "", "", "https://place.map.kakao.com/" + id, "", LocalDateTime.now());
    }

    @Test void looksUpRealPlacesAndNeverKeepsUnsupportedSources() {
        // application.properties와 같은 순서: OPEN_AI_API가 없으면 OPENAI_API_KEY를 쓴다.
        String key = System.getenv("OPEN_AI_API");
        if (key == null || key.isBlank()) key = System.getenv("OPENAI_API_KEY");
        var client = new OpenAiPriceClient(new OpenAiProperties(key, "gpt-6-luna", "none"), new JsonMapper());
        var targets = List.of(
            new Object[] {place(1, "씨마크호텔", "강원특별자치도 강릉시 해안로406번길 2"), OpenAiPriceClient.LODGING},
            new Object[] {place(2, "교동짬뽕", "강원특별자치도 강릉시 강릉대로 205"), OpenAiPriceClient.RESTAURANT},
            new Object[] {place(3, "토담순두부", "강원특별자치도 강릉시 초당순두부길 47"), OpenAiPriceClient.RESTAURANT},
            new Object[] {place(4, "베테랑칼국수", "전북특별자치도 전주시 완산구 경기전길 135"), OpenAiPriceClient.RESTAURANT});
        for (var target : targets) {
            var quote = client.lookup((PlaceCandidate) target[0], (String) target[1]);
            System.out.println("가격 검색: " + quote.name() + " / " + quote.status() + " / " + quote.priceText() + " / " + quote.sourceUrl());
            if (quote.found()) {
                assertThat(quote.sourceUrl()).startsWith("https://");
                assertThat(quote.minWon()).isPositive().isLessThanOrEqualTo(quote.maxWon());
            } else assertThat(quote.sourceUrl()).isNull();
        }
    }
}
