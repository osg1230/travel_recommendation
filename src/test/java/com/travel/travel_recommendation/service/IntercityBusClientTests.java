package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.TagoProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class IntercityBusClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 21);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private IntercityBusClient client(String key) {
        return new IntercityBusClient(new TagoProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/SuburbsBusInfo/"), Duration.ofSeconds(1));
    }
    private void items(String items) {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "}}}}");
    }

    @Test void findsExactTerminalOnceAndReusesIt() {
        items("[{\"terminalId\":\"NAI2210301\",\"terminalNm\":\"강릉\"},{\"terminalId\":\"NAI9999999\",\"terminalNm\":\"강릉대학교\"}]");
        var client = client("a+b/c=");
        assertThat(client.terminalId("강릉")).isEqualTo("NAI2210301");
        assertThat(path.get()).isEqualTo("/SuburbsBusInfo/GetSuberbsBusTrminlList");
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D", "_type=json", "terminalNm=%EA%B0%95%EB%A6%89");
        assertThat(client.terminalId("강릉")).isEqualTo("NAI2210301");
        assertThat(calls.get()).isEqualTo(1);
        assertThat(new TagoProperties("a+b/c=").toString()).doesNotContain("a+b/c=");
    }

    @Test void singleObjectItemAndMissingTerminal() {
        items("{\"terminalId\":\"NAI2320101\",\"terminalNm\":\"속초\"}");
        assertThat(client("key").terminalId("속초")).isEqualTo("NAI2320101");
        items("{\"terminalId\":\"NAI1\",\"terminalNm\":\"속초해수욕장\"}");
        assertCode("BUS_TERMINAL_NOT_FOUND", () -> client("key").terminalId("양양"));
    }

    @Test void readsTodayTripsInDepartureOrderAndSkipsBrokenRows() {
        items("[{\"depPlandTime\":202609211530,\"arrPlandTime\":202609211630,\"gradeNm\":\"일반\",\"charge\":7400},"
            + "{\"depPlandTime\":\"202609210700\",\"arrPlandTime\":\"202609210805\",\"gradeNm\":\"우등\",\"charge\":\"9500\"},"
            + "{\"depPlandTime\":\"202609220700\",\"arrPlandTime\":\"202609220800\"},"      // 다른 날짜
            + "{\"depPlandTime\":\"202609211000\",\"arrPlandTime\":\"202609210900\"},"      // 도착이 출발보다 이름
            + "{\"depPlandTime\":\"bad\",\"arrPlandTime\":\"202609211200\"}]");
        var trips = client("key").trips("A1", "B2", TODAY);
        assertThat(trips).hasSize(2);
        assertThat(trips.get(0).departure()).isEqualTo(LocalDateTime.of(2026, 9, 21, 7, 0));
        assertThat(trips.get(0).minutes()).isEqualTo(65);
        assertThat(trips.get(0).charge()).isEqualTo(9500);
        assertThat(trips.get(1).grade()).isEqualTo("일반");
        assertThat(path.get()).isEqualTo("/SuburbsBusInfo/GetStrtpntAlocFndSuberbsBusInfo");
        assertThat(query.get()).contains("depTerminalId=A1", "arrTerminalId=B2", "depPlandTime=20260921");
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":\"\",\"totalCount\":0}}}");
        assertThat(client("key").trips("A1", "B2", TODAY)).isEmpty();
    }

    @Test void mapsGatewayErrorsWithoutLeakingKey() {
        assertCode("BUS_NOT_CONFIGURED", () -> client(" ").terminalId("강릉"));
        assertThat(calls.get()).isZero();
        status.set(403);
        body.set("{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"errMsg\":\"SERVICE_KEY_IS_NOT_REGISTERED_ERROR\",\"returnReasonCode\":\"30\"}}}");
        assertCode("BUS_AUTH", () -> client("secret-key").trips("A", "B", TODAY));
        status.set(200);
        body.set("<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>LIMITED_NUMBER_OF_SERVICE_REQUESTS_EXCEEDS_ERROR</errMsg></cmmMsgHeader></OpenAPI_ServiceResponse>");
        assertCode("BUS_QUOTA", () -> client("key").trips("A", "B", TODAY));
        body.set("{\"response\":{\"header\":{\"resultCode\":\"99\"}}}");
        assertCode("BUS_RESPONSE", () -> client("key").trips("A", "B", TODAY));
        assertCode("BUS_RESPONSE", () -> client("key").trips("A", "A", TODAY));
    }

    private void assertCode(String code, org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(RecommendationException.class, e -> {
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessage()).doesNotContain("secret-key");
        });
    }
}
