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

class OpenAiDestinationPickClientTests {
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(), request = new AtomicReference<>();
    private final ConfirmedTravelConditions autumn = new ConfirmedTravelConditions("TEXT", "단풍 보러 산에 가고 싶어요", "", null, null, 2, null, 2,
        true, null, List.of(), "", true, "단풍 등산");
    private final List<OpenAiDestinationPickClient.Option> options = List.of(
        option(1, "서울", new OpenAiDestinationPickClient.Evidence("산·오름·봉우리", 30, List.of("북한산"))),
        option(2, "정읍", new OpenAiDestinationPickClient.Evidence("국립공원", 1, List.of("내장산국립공원"))),
        option(3, "구례"), option(4, "속초"), option(5, "인제"), option(6, "단양"));

    private static OpenAiDestinationPickClient.Option option(long id, String name, OpenAiDestinationPickClient.Evidence... evidence) {
        return new OpenAiDestinationPickClient.Option(id, name, "지역 " + name, "", List.of(evidence), List.of());
    }

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
    private OpenAiDestinationPickClient client(String key) {
        return new OpenAiDestinationPickClient(new OpenAiProperties(key, "gpt-6-luna", "none"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5));
    }
    private void answer(List<Long> ids) {
        var text = mapper.writeValueAsString(Map.of("destinationIds", ids));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output",
            List.of(Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", text)))))));
    }

    // 목록에 없는 ID와 중복은 버리고, 받은 순서대로 최대 5곳만 쓴다.
    @Test void unknownAndDuplicateIdsAreDroppedAndAtMostFiveAreKept() {
        answer(List.of(2L, 99L, 2L, 3L, 1L, 4L, 5L, 6L));
        assertThat(client("key").pick(autumn, options)).containsExactly(2L, 3L, 1L, 4L, 5L);
        // 여행 목적과 여행지별 근거는 보내고, 원문은 보내지 않는다.
        assertThat(request.get()).contains("단풍 등산", "내장산국립공원", "국립공원").doesNotContain("단풍 보러 산에 가고 싶어요");
    }

    @Test void errorsAreReportedAndMissingKeyOrEmptyListMakesNoCall() {
        answer(List.of(1L));
        status.set(429);
        assertThatThrownBy(() -> client("key").pick(autumn, options))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(200);
        response.set("{\"status\":\"incomplete\"}");
        assertThatThrownBy(() -> client("key").pick(autumn, options))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
        request.set(null);
        assertThat(client("key").pick(autumn, List.of())).isEmpty();
        assertThatThrownBy(() -> client("").pick(autumn, options)).isInstanceOf(RecommendationException.class);
        assertThat(request.get()).isNull();
    }
}
