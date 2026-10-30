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

class FerryClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private static final LocalDate DAY = LocalDate.of(2026, 10, 30);

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath()); query.set(exchange.getRequestURI().getRawQuery());
            byte[] bytes = body.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length == 0 ? -1 : bytes.length);
            if (bytes.length > 0) exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private FerryClient client(String key) {
        return new FerryClient(new TagoProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1));
    }
    private void items(String items, int total) {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "},\"totalCount\":" + total + "}}}");
    }

    // 실제 응답 형식(12자리 숫자 시각, 요금 -1은 미공개)대로 읽고 출발 시각 순서로 돌려준다.
    // 2026-10-30 실제 응답에 있던 3분짜리 편(23:00 → 23:03)처럼 도착 시각이 잘못된 편과 다른 날짜·12시간 넘는 편은 건너뛴다.
    @Test void readsSailingsInDepartureOrderAndSkipsBrokenTimes() {
        items("[{\"depPlaceNm\":\"울릉_도동\",\"arrPlaceNm\":\"포항\",\"depPlandTime\":202610301420,\"arrPlandTime\":202610301640,\"charge\":171500,\"vihicleNm\":\"엘도라도익스프레스호\"},"
            + "{\"depPlaceNm\":\"울릉_도동\",\"arrPlaceNm\":\"묵호\",\"depPlandTime\":202610301240,\"arrPlandTime\":202610302036,\"charge\":65500,\"vihicleNm\":\"씨스타 1\"},"
            + "{\"depPlaceNm\":\"울릉_도동\",\"arrPlaceNm\":\"포항영일만\",\"depPlandTime\":202610302300,\"arrPlandTime\":202610302303,\"charge\":-1,\"vihicleNm\":\"뉴씨다오펄\"},"
            + "{\"depPlaceNm\":\"울릉_도동\",\"arrPlaceNm\":\"강릉\",\"depPlandTime\":202610310700,\"arrPlandTime\":202610311000,\"vihicleNm\":\"다른 날\"},"
            + "{\"depPlaceNm\":\"울릉_도동\",\"arrPlaceNm\":\"후포\",\"depPlandTime\":202610300500,\"arrPlandTime\":202610301800,\"vihicleNm\":\"13시간\"}]", 5);
        var sailings = client("a+b/c=").sailings("SEA43113", DAY);
        assertThat(sailings).extracting(FerryClient.Sailing::arrivalPort).containsExactly("묵호", "포항");
        assertThat(sailings.get(1).minutes()).isEqualTo(140);
        assertThat(sailings.get(1).vessel()).isEqualTo("엘도라도익스프레스호");
        // 테스트는 서비스 주소(…/DmstcShipNvgInfo/) 자리에 로컬 서버 루트를 넣으므로 오퍼레이션 이름만 본다.
        assertThat(path.get()).endsWith("/GetShipOpratInfoList");
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D").contains("depNodeId=SEA43113").contains("depPlandTime=20261030").contains("_type=json");
    }

    // 결과가 없으면 빈 목록이고, 한 건이면 item이 객체로 와도 읽는다.
    @Test void emptyAndSingleObjectItems() {
        items("[]", 0);
        assertThat(client("k").sailings("SEA43010", DAY)).isEmpty();
        items("{\"depPlaceNm\":\"포항\",\"arrPlaceNm\":\"울릉_도동\",\"depPlandTime\":202610300950,\"arrPlandTime\":202610301210,\"charge\":171500,\"vihicleNm\":\"엘도라도익스프레스호\"}", 1);
        assertThat(client("k").sailings("SEA43010", DAY)).singleElement().satisfies(s -> {
            assertThat(s.arrivalPort()).isEqualTo("울릉_도동");
            assertThat(s.minutes()).isEqualTo(140);
        });
    }

    // 활용신청 전의 본문 없는 403과 게이트웨이 인증 오류는 키를 드러내지 않는 같은 오류 코드로 바꾼다. 항구 ID 형식이 아니면 요청하지 않는다.
    @Test void mapsGatewayErrorsWithoutLeakingKey() {
        status.set(403); body.set("");
        assertThatThrownBy(() -> client("secret-key").sailings("SEA43010", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> {
                assertThat(e.getCode()).isEqualTo("FERRY_AUTH");
                assertThat(e.getMessage()).doesNotContain("secret-key");
            });
        status.set(200); body.set("{\"response\":{\"header\":{\"resultCode\":\"22\"}}}");
        assertThatThrownBy(() -> client("k").sailings("SEA43010", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("FERRY_QUOTA"));
        assertThatThrownBy(() -> client("k").sailings("포항", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("FERRY_RESPONSE"));
        assertThatThrownBy(() -> client("").sailings("SEA43010", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("FERRY_NOT_CONFIGURED"));
    }
}
