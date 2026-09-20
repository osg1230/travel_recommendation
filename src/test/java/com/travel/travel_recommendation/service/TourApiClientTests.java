package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.TourApiProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class TourApiClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private TourApiClient client(String key) {
        return new TourApiClient(new TourApiProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/detailIntro2"), Duration.ofSeconds(1));
    }
    private void response(String type, String fields) {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":1,\"items\":{\"item\":[{\"contentid\":\"123\",\"contenttypeid\":\""
            + type + "\"" + fields + "}]}}}}");
    }
    @Test void encodesDecodedKeyOnceAndReadsAttractionText() {
        response("12", ",\"usetime\":\"09:00~18:00\",\"restdate\":\"매주 월요일\"");
        var result = client("a+b/c=").findOperatingInfo("123", "12").orElseThrow();
        assertThat(result.hours()).isEqualTo("09:00~18:00");
        assertThat(result.closedDays()).isEqualTo("매주 월요일");
        assertThat(result.fetchedAt()).isNotNull();
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D", "contentId=123", "contentTypeId=12", "MobileOS=WEB", "_type=json");
        assertThat(new TourApiProperties("a+b/c=").toString()).doesNotContain("a+b/c=");
    }
    @Test void mapsEachSupportedTypeAndKeepsMissingValuesUnknown() {
        for (var pair : new String[][]{{"14", "usetimeculture", "restdateculture"}, {"28", "usetimeleports", "restdateleports"}, {"39", "opentimefood", "restdatefood"}}) {
            response(pair[0], ",\"" + pair[1] + "\":\"시간 원문\",\"" + pair[2] + "\":\"휴무 원문\"");
            var result = client("key").findOperatingInfo("123", pair[0]).orElseThrow();
            assertThat(result.hours()).isEqualTo("시간 원문");
            assertThat(result.closedDays()).isEqualTo("휴무 원문");
        }
        response("32", ",\"checkintime\":\"15:00\",\"checkouttime\":\"11:00\"");
        var stay = client("key").findOperatingInfo("123", "32").orElseThrow();
        assertThat(stay.hours()).isEmpty(); assertThat(stay.closedDays()).isEmpty();
        assertThat(stay.checkIn()).isEqualTo("15:00"); assertThat(stay.checkOut()).isEqualTo("11:00");
        response("12", "");
        assertThat(client("key").findOperatingInfo("123", "12").orElseThrow().hours()).isEmpty();
    }
    @Test void rejectsMissingKeyAndBadIdsWithoutHttpCalls() {
        assertCode("TOUR_NOT_CONFIGURED", () -> client("").findOperatingInfo("123", "12"));
        assertCode("TOUR_INPUT", () -> client("key").findOperatingInfo("123&x=1", "12"));
        assertCode("TOUR_INPUT", () -> client("key").findOperatingInfo("123", "15"));
        assertThat(calls.get()).isZero();
    }
    @Test void distinguishesNoResultFromInvalidOrMismatchedData() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}");
        assertThat(client("key").findOperatingInfo("123", "12")).isEmpty();
        response("39", ""); assertCode("TOUR_RESPONSE", () -> client("key").findOperatingInfo("123", "12"));
        response("12", ",\"usetime\":123"); assertCode("TOUR_RESPONSE", () -> client("key").findOperatingInfo("123", "12"));
        body.set("{}"); assertCode("TOUR_RESPONSE", () -> client("key").findOperatingInfo("123", "12"));
    }
    @Test void sanitizesJsonXmlAndHttpFailures() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"30\",\"resultMsg\":\"secret-key\"}}}");
        assertCode("TOUR_AUTH", () -> client("secret-key").findOperatingInfo("123", "12"));
        body.set("<error>SERVICE_ACCESS_DENIED_ERROR secret-key</error>");
        assertCode("TOUR_AUTH", () -> client("secret-key").findOperatingInfo("123", "12"));
        body.set("<error>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</error>");
        assertCode("TOUR_QUOTA", () -> client("secret-key").findOperatingInfo("123", "12"));
        body.set("not json secret-key");
        assertCode("TOUR_RESPONSE", () -> client("secret-key").findOperatingInfo("123", "12"));
        status.set(503); assertCode("TOUR_UNAVAILABLE", () -> client("secret-key").findOperatingInfo("123", "12"));
        status.set(429); assertCode("TOUR_QUOTA", () -> client("secret-key").findOperatingInfo("123", "12"));
    }
    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RecommendationException.class, error -> {
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("secret-key"); assertThat(error.getCause()).isNull();
        });
    }
}
