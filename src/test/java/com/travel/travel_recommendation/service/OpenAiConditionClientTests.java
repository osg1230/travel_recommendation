package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.TravelKeyword;
import java.util.List;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiConditionClientTests {
    private HttpServer server;
    private final JsonMapper mapper = new JsonMapper();
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger delay = new AtomicInteger();
    private final AtomicReference<String> response = new AtomicReference<>();
    private final AtomicReference<String> request = new AtomicReference<>();

    @BeforeEach void startServer() throws Exception {
        response.set("{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"output_text\",\"text\":\"{\\\"isTravel\\\":true}\"}]}]}");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/responses", exchange -> {
            calls.incrementAndGet();
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delay.get());
                byte[] body = response.get().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status.get(), body.length);
                exchange.getResponseBody().write(body);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
    }
    @AfterEach void stopServer() { server.stop(0); }
    private OpenAiConditionClient client(String key, Duration timeout) throws Exception {
        return new OpenAiConditionClient(new OpenAiProperties(key, "gpt-4.1-mini"), mapper,
                URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), timeout);
    }

    @Test void sendsStructuredRequestWithoutResponseStorage() throws Exception {
        var result = client("test-key", Duration.ofSeconds(2)).analyze("강릉 여행",
                Map.of("season", List.of(new TravelKeyword(20L, "SEASON", "여름", "{\"value\":\"SUMMER\"}"))));
        assertThat(result.get("isTravel").booleanValue()).isTrue();
        var body = mapper.readTree(request.get());
        assertThat(body.path("store").booleanValue()).isFalse();
        assertThat(body.path("text").path("format").path("strict").booleanValue()).isTrue();
        assertThat(body.path("text").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("text").path("format").path("schema").path("properties").path("season")
                .path("enum").toString()).isEqualTo("[null,\"20\"]");
        assertThat(mapper.readTree(body.path("input").asText()).path("travelText").asText()).isEqualTo("강릉 여행");
        assertThat(mapper.readTree(body.path("input").asText()).path("choices").path("season").get(0)
                .path("selectionValue").asText()).isEqualTo("20");
        assertThat(request.get()).doesNotContain("test-key", "loginId", "password");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(new OpenAiProperties("test-key", "gpt-4.1-mini").toString()).doesNotContain("test-key");
    }
    @Test void missingKeyDoesNotSendRequest() throws Exception {
        var client = client("", Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.analyze("강릉", Map.of())).isInstanceOf(ConditionAnalysisException.class)
                .hasMessageContaining("준비");
        assertThat(calls.get()).isZero();
    }
    @Test void httpErrorsDoNotLeakProviderBodyOrRetry() throws Exception {
        var client = client("test-key", Duration.ofSeconds(2));
        response.set("private-provider-details");
        for (int code : new int[]{401, 403, 429, 500}) {
            status.set(code);
            assertThatThrownBy(() -> client.analyze("강릉", Map.of()))
                    .isInstanceOf(ConditionAnalysisException.class).hasMessageNotContaining("private-provider-details");
        }
        assertThat(calls.get()).isEqualTo(4);
    }
    @Test void rejectsIncompleteRefusalAndMalformedResponses() throws Exception {
        var client = client("test-key", Duration.ofSeconds(2));
        for (String body : new String[]{"not-json", "{\"status\":\"incomplete\"}",
                "{\"status\":\"completed\",\"output\":[]}",
                "{\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\"}]}]}"}) {
            response.set(body);
            assertThatThrownBy(() -> client.analyze("강릉", Map.of())).isInstanceOf(ConditionAnalysisException.class);
        }
    }
    @Test void timeoutReturnsSafeRetryNotice() throws Exception {
        delay.set(800);
        var client = client("test-key", Duration.ofMillis(100));
        assertThatThrownBy(() -> client.analyze("강릉", Map.of())).isInstanceOf(ConditionAnalysisException.class)
                .hasMessageContaining("오래 걸려");
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
}
