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

class FlightClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 3);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath()); query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private FlightClient client(String key) {
        return new FlightClient(new TagoProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1));
    }
    private void items(String items, int total) {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "},\"totalCount\":" + total + "}}}");
    }

    // 실제 응답 형식(12자리 숫자 시각, 요금 0은 미판매)대로 읽고 출발 시각 순서로 돌려준다. 잘못된 편은 건너뛴다.
    @Test void readsFlightsInDepartureOrderAndSkipsBrokenRows() {
        items("[{\"airlineNm\":\"아시아나 항공\",\"arrPlandTime\":202610030935,\"depPlandTime\":202610030820,\"economyCharge\":61900,\"prestigeCharge\":0,\"vihicleId\":\"OZ8921\"},"
            + "{\"airlineNm\":\"대한항공\",\"arrPlandTime\":202610030745,\"depPlandTime\":202610030630,\"economyCharge\":0,\"vihicleId\":\"KE1201\"},"
            + "{\"airlineNm\":\"잘못된 편\",\"arrPlandTime\":202610030600,\"depPlandTime\":202610030700,\"vihicleId\":\"X1\"},"
            + "{\"airlineNm\":\"다른 날\",\"arrPlandTime\":202610040800,\"depPlandTime\":202610040700,\"vihicleId\":\"X2\"}]", 4);
        var flights = client("a+b/c=").flights("NAARKSS", "NAARKPC", DAY);
        assertThat(flights).hasSize(2);
        assertThat(flights.getFirst().flightNo()).isEqualTo("KE1201");
        assertThat(flights.getFirst().minutes()).isEqualTo(75);
        assertThat(flights.getFirst().economyCharge()).isNull();
        assertThat(flights.get(1).economyCharge()).isEqualTo(61900);
        // 테스트는 서비스 주소(…/DmstcFlightNvgInfo/) 자리에 로컬 서버 루트를 넣으므로 오퍼레이션 이름만 본다.
        assertThat(path.get()).endsWith("/GetFlightOpratInfoList");
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D").contains("depAirportId=NAARKSS").contains("arrAirportId=NAARKPC").contains("depPlandTime=20261003");
    }

    // 결과가 없으면 빈 목록이고, 한 건이면 item이 객체로 와도 읽는다.
    @Test void emptyAndSingleObjectItems() {
        items("[]", 0);
        assertThat(client("k").flights("NAARKSS", "NAARKPC", DAY)).isEmpty();
        items("{\"airlineNm\":\"진에어\",\"arrPlandTime\":202610031000,\"depPlandTime\":202610030850,\"economyCharge\":55000,\"vihicleId\":\"LJ301\"}", 1);
        assertThat(client("k").flights("NAARKSS", "NAARKPC", DAY)).singleElement().satisfies(f -> assertThat(f.airline()).isEqualTo("진에어"));
    }

    // 활용신청 전의 본문 없는 403과 게이트웨이 인증 오류는 키를 드러내지 않는 같은 오류 코드로 바꾼다.
    @Test void mapsGatewayErrorsWithoutLeakingKey() {
        status.set(403); body.set("");
        assertThatThrownBy(() -> client("secret-key").flights("NAARKSS", "NAARKPC", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> {
                assertThat(e.getCode()).isEqualTo("FLIGHT_AUTH");
                assertThat(e.getMessage()).doesNotContain("secret-key");
            });
        status.set(200); body.set("{\"response\":{\"header\":{\"resultCode\":\"22\"}}}");
        assertThatThrownBy(() -> client("k").flights("NAARKSS", "NAARKPC", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("FLIGHT_QUOTA"));
        assertThatThrownBy(() -> client("k").flights("NAARKSS", "NAARKSS", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("FLIGHT_RESPONSE"));
    }
}
