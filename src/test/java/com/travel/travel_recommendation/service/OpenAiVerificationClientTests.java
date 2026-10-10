package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.ItineraryPlan.CheckQuestion;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiVerificationClientTests {
    private static final String PAGE = "https://www.example-hotel.kr/pet";
    private final JsonMapper mapper = new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> response = new AtomicReference<>(), request = new AtomicReference<>();
    private final AtomicBoolean pageMentions = new AtomicBoolean(true);
    private final CheckQuestion question = new CheckQuestion(5L, "씨마크 호텔", "PET", "강원특별자치도 강릉시 해안로 15에 있는 '씨마크 호텔'에 반려동물(강아지) 동반 입장이나 이용이 가능한가요?");

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

    private OpenAiVerificationClient client(String key) {
        var pages = new PricePageChecker() {
            @Override public boolean mentions(String url, String name) { return pageMentions.get(); }
        };
        return new OpenAiVerificationClient(new OpenAiProperties(key, "gpt-6-luna", "none"), mapper,
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), Duration.ofSeconds(5), pages);
    }
    private void answer(List<String> sources, String verdict, String text, String url) {
        var search = Map.of("type", "web_search_call", "status", "completed",
            "action", Map.of("type", "search", "sources", sources.stream().map(u -> Map.of("type", "url", "url", u)).toList()));
        var json = new LinkedHashMap<String, Object>();
        json.put("verdict", verdict); json.put("answer", text); json.put("sourceUrl", url);
        var message = Map.of("type", "message", "content", List.of(Map.of("type", "output_text", "text", mapper.writeValueAsString(json))));
        response.set(mapper.writeValueAsString(Map.of("status", "completed", "output", List.of(search, message))));
    }

    // 출처가 검색 결과 목록에 있고 페이지에 장소 이름이 있으면 VERIFIED, 페이지를 확인하지 못하면 UNVERIFIED다. 질문만 보내고 웹 검색 도구를 요구한다.
    @Test void answerWithSourceInSearchResultsIsVerifiedWhenPageMentionsPlace() {
        answer(List.of(PAGE + "?utm_source=openai"), "YES", "공식 안내에 소형견 동반 가능, 목줄 필수라고 적혀 있어요.", PAGE);
        var result = client("key").lookup(question);
        assertThat(result.verdict()).isEqualTo("YES"); assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.sourceUrl()).isEqualTo(PAGE); assertThat(result.topic()).isEqualTo("PET"); assertThat(result.checkedAt()).isNotNull();
        assertThat(request.get()).contains("web_search", "tool_choice", "씨마크 호텔", "place_check");
        pageMentions.set(false);
        assertThat(client("key").lookup(question).status()).isEqualTo("UNVERIFIED");
    }

    // 출처가 검색 목록에 없거나, UNKNOWN이거나, 답에 링크·태그가 있으면 근거 없음(NOT_FOUND)으로 둔다.
    @Test void unsupportedAnswersBecomeNotFound() {
        answer(List.of("https://other.example.kr/"), "YES", "가능해요.", PAGE);
        assertThat(client("key").lookup(question).status()).isEqualTo("NOT_FOUND");
        answer(List.of(PAGE), "UNKNOWN", "", null);
        var unknown = client("key").lookup(question);
        assertThat(unknown.status()).isEqualTo("NOT_FOUND"); assertThat(unknown.verdict()).isEqualTo("UNKNOWN"); assertThat(unknown.found()).isFalse();
        answer(List.of(PAGE), "NO", "자세한 내용은 http://x 참고", PAGE);
        assertThat(client("key").lookup(question).status()).isEqualTo("NOT_FOUND");
        answer(List.of(PAGE), "NO", "반려동물 출입을 금지해요.", PAGE);
        assertThat(client("key").lookup(question).verdictLabel()).isEqualTo("불가");
    }

    @Test void missingKeyAndHttpErrorsAreReportedWithoutRetry() {
        assertThatThrownBy(() -> client(" ").lookup(question)).isInstanceOfSatisfying(RecommendationException.class,
            e -> assertThat(e.getCode()).isEqualTo("AI_NOT_CONFIGURED"));
        status.set(500); response.set("{}");
        assertThatThrownBy(() -> client("key").lookup(question)).isInstanceOfSatisfying(RecommendationException.class,
            e -> assertThat(e.getCode()).isEqualTo("AI_UNAVAILABLE"));
        status.set(429); response.set("{\"error\":{\"code\":\"insufficient_quota\"}}");
        assertThatThrownBy(() -> client("key").lookup(question)).isInstanceOfSatisfying(RecommendationException.class,
            e -> assertThat(e.getCode()).isEqualTo("AI_QUOTA"));
    }
}
