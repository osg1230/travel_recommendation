package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiPriceClientTests {
    private static final String PAGE = "https://www.example-menu.kr/place/1";
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(), request = new AtomicReference<>();
    private final AtomicBoolean pageConfirms = new AtomicBoolean(true);
    private final PlaceCandidate restaurant = new PlaceCandidate(3L, "토담순두부", "강원특별자치도 강릉시 초당순두부길 47", "음식점", "",
        "https://place.map.kakao.com/3", "", LocalDateTime.of(2026, 9, 22, 0, 0));

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", e -> {
            request.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = response.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(), bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    // 출처 페이지 확인은 따로 시험하고, 여기서는 결과만 정해 둔다.
    private OpenAiPriceClient client(String key) {
        var pages = new PricePageChecker() {
            @Override public boolean confirms(String url, String name, String address, Integer min, Integer max) { return pageConfirms.get(); }
        };
        return new OpenAiPriceClient(new OpenAiProperties(key, "gpt-4.1-mini"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5), pages);
    }
    /** 웹 검색 호출(검색한 페이지 목록)과 최종 JSON 답을 담은 응답을 만든다. */
    private void answer(List<String> sources, Map<String, Object> price) {
        var search = Map.of("type", "web_search_call", "status", "completed",
            "action", Map.of("type", "search", "sources", sources.stream().map(url -> Map.of("type", "url", "url", url)).toList()));
        var message = Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", mapper.writeValueAsString(price))));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(search, message))));
    }
    private Map<String, Object> price(Object min, Object max, String url) {
        var price = new LinkedHashMap<String, Object>();
        price.put("found", true); price.put("priceText", "순두부백반 1만1천원"); price.put("minWon", min); price.put("maxWon", max);
        price.put("basis", "순두부백반 1인분"); price.put("sourceUrl", url);
        return price;
    }

    @Test void priceWhoseSourcePageShowsNameAndPriceIsVerified() {
        answer(List.of(PAGE + "?utm_source=openai"), price(11000, 11000, PAGE));
        var quote = client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT);
        assertThat(quote.status()).isEqualTo("VERIFIED");
        assertThat(quote.minWon()).isEqualTo(11000); assertThat(quote.sourceUrl()).isEqualTo(PAGE);
        // 검색 도구를 강제로 쓰고, 검색한 페이지 목록을 함께 요청한다. 이름·주소만 보낸다.
        assertThat(request.get()).contains("\"web_search\"", "web_search_call.action.sources", "\"tool_choice\":\"required\"", "토담순두부");
        // 페이지에서 확인하지 못하면 가격은 보여 주되 직접 확인 필요로 둔다.
        pageConfirms.set(false);
        assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("UNVERIFIED");
    }

    @Test void sourcesOutsideSearchResultsOrOddValuesAreNotUsed() {
        // AI가 적은 출처가 실제 검색 결과 목록에 없으면 지어낸 출처일 수 있다.
        answer(List.of("https://other.example.kr/"), price(11000, 11000, PAGE));
        assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("NOT_FOUND");
        answer(List.of(PAGE), price(11000, 11000, "hotels.com 또는 Google 호텔에서 확인된 가격"));
        assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("NOT_FOUND");
        // 음식점 1인분으로 보기 어려운 가격, 최저>최고, 빈 가격은 쓰지 않는다.
        for (var bad : List.of(price(500_000, 500_000, PAGE), price(12000, 11000, PAGE), price(null, null, PAGE))) {
            answer(List.of(PAGE), bad);
            assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("NOT_FOUND");
        }
        var html = price(11000, 11000, PAGE); html.put("priceText", "<b>1만원</b>");
        answer(List.of(PAGE), html);
        assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("NOT_FOUND");
        var none = price(null, null, null); none.put("found", false);
        answer(List.of(PAGE), none);
        var quote = client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT);
        assertThat(quote.found()).isFalse(); assertThat(quote.priceText()).isNull(); assertThat(quote.sourceUrl()).isNull();
        // JSON이 아닌 답도 실패 대신 찾지 못함으로 둔다.
        response.set("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"모르겠어요\"}]}]}");
        assertThat(client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT).status()).isEqualTo("NOT_FOUND");
    }

    @Test void connectionProblemsAreReportedAndMissingKeyMakesNoCall() {
        answer(List.of(PAGE), price(11000, 11000, PAGE));
        status.set(429);
        assertThatThrownBy(() -> client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(401);
        assertThatThrownBy(() -> client("key").lookup(restaurant, OpenAiPriceClient.RESTAURANT))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_AUTH"));
        request.set(null);
        assertThatThrownBy(() -> client("").lookup(restaurant, OpenAiPriceClient.RESTAURANT))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_NOT_CONFIGURED"));
        assertThat(request.get()).isNull();
    }
}
