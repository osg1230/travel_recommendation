package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiPurposeClientTests {
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
    private OpenAiPurposeClient client(String key) {
        return new OpenAiPurposeClient(new OpenAiProperties(key, "gpt-6-luna", "none"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5));
    }
    private void answer(List<String> keywords) { answer(keywords, List.of()); }
    private void answer(List<String> keywords, List<String> themes) {
        var text = mapper.writeValueAsString(Map.of("keywords", keywords, "themes", themes));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output",
            List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))));
    }

    @Test void purposeBecomesUpToThreeCleanFacilityKeywords() {
        answer(List.of("아쿠아리움", " 수족관 ", "아쿠아리움", "https://evil.example", "<b>상어</b>", "해양 박물관", "천문대"));
        // 중복·URL·태그는 버리고 최대 3개만 쓴다. 목적만 보내고 원문·계정 정보는 보내지 않는다.
        assertThat(client("key").terms("상어 관람").keywords()).containsExactly("아쿠아리움", "수족관", "해양 박물관");
        assertThat(request.get()).contains("상어 관람");
        answer(List.of());
        assertThat(client("key").terms("휴식").keywords()).isEmpty();
    }

    // 목적과 가까운 여행지 분류는 정해진 목록 안에서 최대 2개만 받는다.
    @Test void themesAreLimitedToKnownDestinationCategories() {
        answer(List.of("갯벌체험"), List.of("바다", "갯벌", "바다", "자연", "휴식"));
        var terms = client("key").terms("갯벌 체험");
        assertThat(terms.keywords()).containsExactly("갯벌체험");
        assertThat(terms.themes()).containsExactly("바다", "자연");
        assertThat(request.get()).contains("\"themes\"", "체험·액티비티");
        answer(List.of(), List.of("휴식"));
        assertThat(client("key").terms("쉬고 싶어").themes()).containsExactly("휴식");
    }

    @Test void errorsAreReportedAndMissingKeyMakesNoCall() {
        answer(List.of("아쿠아리움"));
        status.set(429);
        assertThatThrownBy(() -> client("key").terms("상어 관람"))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(200);
        response.set("{\"status\":\"incomplete\"}");
        assertThatThrownBy(() -> client("key").terms("상어 관람"))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
        request.set(null);
        assertThatThrownBy(() -> client("").terms("상어 관람")).isInstanceOf(RecommendationException.class);
        assertThat(request.get()).isNull();
    }
}
