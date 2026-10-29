package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiLandmarkClientTests {
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(), request = new AtomicReference<>();

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
    private OpenAiLandmarkClient client(String key) {
        return new OpenAiLandmarkClient(new OpenAiProperties(key, "gpt-6-luna", "none"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5));
    }
    private void answer(List<String> names) {
        var text = mapper.writeValueAsString(Map.of("names", names));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output",
            List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))));
    }

    // 순서는 그대로 두고, 띄어쓰기만 다른 같은 이름·URL·태그·너무 긴 이름은 버린다. 여행지 이름과 행정구역만 보낸다.
    @Test void namesKeepOrderAndDropDuplicatesAndUnsafeValues() {
        answer(List.of("경포대", " 오죽헌 ", "경 포대", "https://evil.example", "<b>안목해변</b>", "정동진", "가".repeat(31), "()"));
        assertThat(client("key").names("강릉", "강원특별자치도 강릉시")).containsExactly("경포대", "오죽헌", "정동진");
        assertThat(request.get()).contains("여행지: 강릉", "강원특별자치도 강릉시", "destination_landmarks");
    }

    @Test void namesAreLimitedToTwenty() {
        answer(IntStream.rangeClosed(1, 25).mapToObj(i -> "관광지" + i).toList());
        assertThat(client("key").names("부산", "부산광역시")).hasSize(20).startsWith("관광지1").endsWith("관광지20");
    }

    @Test void missingKeyAndBadResponsesBecomeRecommendationErrors() {
        assertThatThrownBy(() -> client("").names("강릉", "강원특별자치도 강릉시"))
            .isInstanceOf(RecommendationException.class).extracting("code").isEqualTo("AI_NOT_CONFIGURED");
        response.set("{\"status\":\"incomplete\"}");
        assertThatThrownBy(() -> client("key").names("강릉", "강원특별자치도 강릉시")).extracting("code").isEqualTo("AI_INVALID_RESPONSE");
        status.set(401); answer(List.of("경포대"));
        assertThatThrownBy(() -> client("key").names("강릉", "강원특별자치도 강릉시")).extracting("code").isEqualTo("AI_AUTH");
    }
}
