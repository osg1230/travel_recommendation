package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiRegionClientTests {
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(), request = new AtomicReference<>();
    private final ConfirmedTravelConditions hiking = new ConfirmedTravelConditions("TEXT", "지리산 가고 싶어요", "", null, null, 2, null, 2,
        true, null, List.of(), "", true, "지리산 등산").withExcludedRegions(List.of("부산"));

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
    private OpenAiRegionClient client(String key) {
        return new OpenAiRegionClient(new OpenAiProperties(key, "gpt-6-luna", "none"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5));
    }
    private void answer(List<String> categories, List<Map<String, String>> regions) {
        var text = mapper.writeValueAsString(Map.of("categories", categories, "regions", regions));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output",
            List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))));
    }
    private static Map<String, String> region(String province, String city) { return Map.of("province", province, "city", city); }

    // 시·도 이름은 지금의 정식 이름으로 바꾸고, 광역시·제주는 시·군 없이, 형식이 맞지 않는 시·군과 모르는 분류는 버린다.
    @Test void regionsAreNormalizedToOfficialNamesAndBadValuesAreDropped() {
        answer(List.of("NA010100", "NA040100", "FD010100", "NA010100"), List.of(
            region("전라남도", "구례군"), region("경상남도", "산청군"), region("전라북도", "남원시"), region("부산", "해운대구"),
            region("경남", "<b>함양군</b>"), region("없는도", "어딘가군"), region("제주도", "서귀포시")));
        var suggestion = client("key").suggest(hiking);
        assertThat(suggestion.categories()).containsExactly("NA010100", "NA040100");
        assertThat(suggestion.regions()).containsExactly("전남광주통합특별시 구례군", "경상남도 산청군", "전북특별자치도 남원시",
            "부산광역시", "제주특별자치도");
        // 여행 목적·빼 달라고 한 지역·분류 목록은 보내고, 원문은 보내지 않는다.
        assertThat(request.get()).contains("지리산 등산", "부산", "산·오름·봉우리").doesNotContain("지리산 가고 싶어요");
    }

    @Test void errorsAreReportedAndMissingKeyMakesNoCall() {
        answer(List.of("NA010100"), List.of(region("경상남도", "산청군")));
        status.set(429);
        assertThatThrownBy(() -> client("key").suggest(hiking))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(200);
        response.set("{\"status\":\"incomplete\"}");
        assertThatThrownBy(() -> client("key").suggest(hiking))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
        request.set(null);
        assertThatThrownBy(() -> client("").suggest(hiking)).isInstanceOf(RecommendationException.class);
        assertThat(request.get()).isNull();
    }
}
