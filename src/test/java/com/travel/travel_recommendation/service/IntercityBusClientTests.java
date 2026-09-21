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
    private final java.util.Map<Integer, String> pages = new java.util.concurrent.ConcurrentHashMap<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath()); query.set(exchange.getRequestURI().getRawQuery());
            // 페이지별 응답이 있으면 요청한 pageNo의 응답을 준다.
            var matcher = java.util.regex.Pattern.compile("pageNo=(\\d+)").matcher(query.get());
            String text = matcher.find() && pages.containsKey(Integer.valueOf(matcher.group(1))) ? pages.get(Integer.valueOf(matcher.group(1))) : body.get();
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
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
        // 실제 응답처럼 초까지 붙은 14자리 숫자와 12자리 문자열을 섞어 보낸다.
        items("[{\"depPlandTime\":20260921153000,\"arrPlandTime\":20260921163000,\"gradeNm\":\"일반\",\"charge\":7400},"
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

    @Test void ambiguousTerminalNameHasItsOwnCode() {
        items("[{\"terminalId\":\"NAI1\",\"terminalNm\":\"울산\"},{\"terminalId\":\"NAI2\",\"terminalNm\":\"울산\"}]");
        assertCode("BUS_TERMINAL_AMBIGUOUS", () -> client("key").terminalId("울산"));
        items("[{\"terminalId\":\"bad id!\",\"terminalNm\":\"울산\"}]");
        assertCode("BUS_RESPONSE", () -> client("key").terminalId("울산"));
    }

    @Test void readsEveryPageSoLastDepartureAndCountAreComplete() {
        // 첫 페이지 100건(06:00부터 1분 간격), 둘째 페이지 1건(22:30). 둘째 페이지를 빼면 막차가 틀린다.
        StringBuilder first = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            var dep = LocalDateTime.of(2026, 9, 21, 6, 0).plusMinutes(i);
            if (i > 0) first.append(',');
            first.append("{\"depPlandTime\":").append(dep.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss")))
                .append(",\"arrPlandTime\":").append(dep.plusMinutes(50).format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))).append('}');
        }
        pages.put(1, "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + first + "]},\"totalCount\":101}}}");
        pages.put(2, "{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":{\"depPlandTime\":20260921223000,\"arrPlandTime\":20260921232000}},\"totalCount\":101}}}");
        var trips = client("key").trips("A", "B", TODAY);
        assertThat(trips).hasSize(101);
        assertThat(trips.getLast().departure()).isEqualTo(LocalDateTime.of(2026, 9, 21, 22, 30));
        assertThat(calls.get()).isEqualTo(2);
        // 제한 페이지를 다 읽어도 남아 있으면 일부만으로 요약하지 않는다.
        pages.clear(); calls.set(0);
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + first + "]},\"totalCount\":900}}}");
        assertCode("BUS_TOO_MANY", () -> client("key").trips("A", "B", TODAY));
        assertThat(calls.get()).isEqualTo(IntercityBusClient.MAX_TRIP_PAGES);
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
