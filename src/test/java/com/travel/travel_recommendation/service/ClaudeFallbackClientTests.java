package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.ClaudeProperties;
import com.travel.travel_recommendation.config.OpenAiProperties;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** OpenAI 크레딧 소진 때 Claude가 대신 답하는 흐름을 로컬 가짜 서버로 확인한다. 실제 API는 부르지 않는다. */
class ClaudeFallbackClientTests {
    private static final String QUOTA = "{\"error\":{\"code\":\"insufficient_quota\"}}";
    // 크레딧이 남은 OpenAI 키가 돌려주는 정상 응답.
    private static final String OPENAI_ANSWER = "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\","
        + "\"text\":\"{\\\"keywords\\\":[\\\"수족관\\\"],\\\"themes\\\":[],\\\"food\\\":\\\"\\\",\\\"festival\\\":false}\"}]}]}";
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger openAiStatus = new AtomicInteger(429), claudeStatus = new AtomicInteger(200), claudeCalls = new AtomicInteger();
    private final AtomicReference<String> openAiBody = new AtomicReference<>(QUOTA), claudeBody = new AtomicReference<>(),
        claudeRequest = new AtomicReference<>(), claudeKey = new AtomicReference<>(), workingOpenAiKey = new AtomicReference<>("");
    private final List<String> openAiKeysTried = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", e -> {
            e.getRequestBody().readAllBytes();
            String key = e.getRequestHeaders().getFirst("Authorization").replace("Bearer ", "");
            openAiKeysTried.add(key);
            // workingOpenAiKey로 온 요청만 정상 응답하고, 나머지 키는 설정한 오류(기본: 크레딧 소진)로 답한다.
            boolean works = key.equals(workingOpenAiKey.get());
            byte[] bytes = (works ? OPENAI_ANSWER : openAiBody.get()).getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(works ? 200 : openAiStatus.get(), bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.createContext("/v1/messages", e -> {
            claudeCalls.incrementAndGet();
            claudeKey.set(e.getRequestHeaders().getFirst("Authorization"));
            claudeRequest.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = claudeBody.get().getBytes(StandardCharsets.UTF_8);
            e.getResponseHeaders().add("Content-Type", "application/json");
            e.sendResponseHeaders(claudeStatus.get(), bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }

    private String base() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
    private OpenAiPurposeClient client(String claudeApiKey) { return client(claudeApiKey, List.of()); }
    private OpenAiPurposeClient client(String claudeApiKey, List<String> extraOpenAiKeys) {
        var client = new OpenAiPurposeClient(new OpenAiProperties("openai-key", "gpt-6-luna", "none", extraOpenAiKeys), mapper,
            URI.create(base() + "/responses"), Duration.ofSeconds(5));
        client.claudeFallback = new ClaudeFallbackClient(new ClaudeProperties(claudeApiKey, "claude-opus-5-5", 5, false), mapper, base());
        return client;
    }
    private void claudeAnswers(String stopReason, String text) {
        claudeBody.set(mapper.writeValueAsString(Map.of("id", "msg_1", "type", "message", "role", "assistant", "model", "claude-opus-5-5",
            "content", List.of(Map.of("type", "text", "text", text)), "stop_reason", stopReason,
            "usage", Map.of("input_tokens", 10, "output_tokens", 10))));
    }

    @Test void claudeAnswersWhenOpenAiCreditIsExhausted() {
        claudeAnswers("end_turn", "{\"keywords\":[\"아쿠아리움\"],\"themes\":[\"바다\"],\"food\":\"\",\"festival\":false}");
        var terms = client("claude-key").terms("상어 관람");
        // Claude의 답도 OpenAI 답과 같은 검증을 거쳐 같은 결과 형식으로 돌아온다.
        assertThat(terms.keywords()).containsExactly("아쿠아리움");
        assertThat(terms.themes()).containsExactly("바다");
        // 공식 SDK는 설정한 Claude 키를 Authorization 헤더로 보낸다.
        assertThat(claudeKey.get()).isEqualTo("Bearer claude-key");
        // 같은 지시문·입력·출력 형식을 보내되, Claude가 받지 않는 개수 제한(maxItems)은 뺀다.
        var sent = mapper.readTree(claudeRequest.get());
        assertThat(sent.path("model").asText()).isEqualTo("claude-opus-5-5");
        assertThat(claudeRequest.get()).contains("상어 관람", "검색어로 바꾼다").doesNotContain("maxItems", "openai-key");
        var schema = sent.path("output_config").path("format").path("schema");
        assertThat(sent.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(schema.path("properties").path("themes").path("items").path("enum")).hasSize(OpenAiPurposeClient.THEMES.size());
    }

    @Test void nextOpenAiKeyIsTriedBeforeClaude() {
        // 첫 키가 소진되면 두 번째 키로 같은 요청을 보낸다. 그 키가 답하면 Claude는 부르지 않는다.
        workingOpenAiKey.set("second-key");
        var terms = client("claude-key", List.of("second-key", "third-key")).terms("상어 관람");
        assertThat(terms.keywords()).containsExactly("수족관");
        assertThat(openAiKeysTried).containsExactly("openai-key", "second-key");
        assertThat(claudeCalls.get()).isZero();
    }

    @Test void claudeIsCalledOnlyAfterEveryOpenAiKeyIsExhausted() {
        claudeAnswers("end_turn", "{\"keywords\":[\"아쿠아리움\"],\"themes\":[],\"food\":\"\",\"festival\":false}");
        // 빈 값과 같은 키가 다시 들어온 것은 건너뛴다.
        var terms = client("claude-key", List.of("", "second-key", "openai-key")).terms("상어 관람");
        assertThat(openAiKeysTried).containsExactly("openai-key", "second-key");
        assertThat(claudeCalls.get()).isEqualTo(1);
        assertThat(terms.keywords()).containsExactly("아쿠아리움");
    }

    @Test void quotaMessageStaysWhenClaudeIsNotConfiguredOrFails() {
        // Claude 키가 없으면 대체 호출을 하지 않는다.
        assertThatThrownBy(() -> client("").terms("상어 관람")).isInstanceOf(RecommendationException.class).hasMessageContaining("한도");
        assertThat(claudeCalls.get()).isZero();
        // Claude도 실패하면 기존 한도 안내를 그대로 보여 준다.
        claudeStatus.set(401);
        claudeBody.set("{\"type\":\"error\",\"error\":{\"type\":\"authentication_error\",\"message\":\"invalid x-api-key\"}}");
        assertThatThrownBy(() -> client("bad-key").terms("상어 관람")).isInstanceOf(RecommendationException.class).hasMessageContaining("한도");
        assertThat(claudeCalls.get()).isEqualTo(1);
    }

    @Test void claudeIsNotCalledForOtherOpenAiErrors() {
        // 잠시 기다리면 풀리는 요청 제한(429)이나 정상 응답에서는 Claude를 부르지 않는다.
        openAiBody.set("{\"error\":{\"code\":\"rate_limit_exceeded\"}}");
        assertThatThrownBy(() -> client("claude-key").terms("상어 관람")).isInstanceOf(RecommendationException.class).hasMessageContaining("제한");
        assertThat(claudeCalls.get()).isZero();
    }

    @Test void cutOffClaudeAnswerIsNotUsed() {
        // 출력 토큰이 모자라 끊긴 답은 완성된 답으로 쓰지 않는다.
        claudeAnswers("max_tokens", "{\"keywords\":[\"아쿠아");
        assertThatThrownBy(() -> client("claude-key").terms("상어 관람")).isInstanceOf(RecommendationException.class);
    }

    @Test void nullInsideEnumBecomesAnyOf() {
        // 실제 Claude 호출(2026-11-28)에서 enum 안의 null이 거부됐다. '값 중 하나 또는 null'을 anyOf로 바꾼다.
        var values = new java.util.ArrayList<String>(List.of("봄", "가을"));
        values.add(0, null);
        Object cleaned = ClaudeFallbackClient.supported(Map.of("type", List.of("string", "null"), "enum", values));
        assertThat((Object) mapper.valueToTree(cleaned)).isEqualTo(mapper.valueToTree(
            Map.of("anyOf", List.of(Map.of("type", "string", "enum", List.of("봄", "가을")), Map.of("type", "null")))));
    }

    @Test void unsupportedLimitsAreRemovedButFieldNamesAreKept() {
        // properties 아래의 'maxLength'는 항목 이름이므로 남기고, 제한 키워드만 뺀다.
        Object cleaned = ClaudeFallbackClient.supported(Map.of("type", "object", "properties", Map.of(
            "maxLength", Map.of("type", "string", "maxLength", 10),
            "items", Map.of("type", "array", "minItems", 1, "maxItems", 3, "items", Map.of("type", "integer", "minimum", 0)))));
        assertThat((Object) mapper.valueToTree(cleaned))
            .isEqualTo(mapper.valueToTree(Map.of("type", "object", "properties", Map.of(
                "maxLength", Map.of("type", "string"),
                "items", Map.of("type", "array", "items", Map.of("type", "integer"))))));
    }
}
