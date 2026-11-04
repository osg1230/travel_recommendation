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

class TrainClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private static final LocalDate DAY = LocalDate.of(2026, 11, 5);

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
    private TrainClient client(String key) {
        return new TrainClient(new TagoProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1));
    }
    private void items(String items, int total) {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"00\"},\"body\":{\"items\":{\"item\":" + items + "},\"totalCount\":" + total + "}}}");
    }

    // 실제 응답 형식(14자리 문자열 시각과 요금)대로 읽고 출발 시각 순서로 돌려준다. 잘못된 열차는 건너뛴다.
    @Test void readsTrainsInDepartureOrderAndSkipsBrokenRows() {
        items("[{\"trainno\":\"00803\",\"traingradename\":\"KTX-이음\",\"depplandtime\":\"20261105054000\",\"arrplandtime\":\"20261105074400\",\"adultcharge\":\"27600\"},"
            + "{\"trainno\":\"00801\",\"traingradename\":\"KTX-이음\",\"depplandtime\":\"20261105050600\",\"arrplandtime\":\"20261105070300\",\"adultcharge\":\"0\"},"
            + "{\"trainno\":\"X1\",\"traingradename\":\"잘못된 열차\",\"depplandtime\":\"20261105090000\",\"arrplandtime\":\"20261105080000\"},"
            + "{\"trainno\":\"X2\",\"traingradename\":\"다른 날\",\"depplandtime\":\"20261106090000\",\"arrplandtime\":\"20261106110000\"}]", 4);
        var trains = client("a+b/c=").trains("NAT010000", "NAT601936", DAY);
        assertThat(trains).hasSize(2);
        assertThat(trains.getFirst().trainNo()).isEqualTo("00801");
        assertThat(trains.getFirst().minutes()).isEqualTo(117);
        assertThat(trains.getFirst().adultCharge()).isNull();
        assertThat(trains.get(1).adultCharge()).isEqualTo(27600);
        assertThat(trains.get(1).grade()).isEqualTo("KTX-이음");
        // 테스트는 서비스 주소(…/TrainInfo/) 자리에 로컬 서버 루트를 넣으므로 오퍼레이션 이름만 본다.
        assertThat(path.get()).endsWith("/GetStrtpntAlocFndTrainInfo");
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D").contains("depPlaceId=NAT010000").contains("arrPlaceId=NAT601936").contains("depPlandTime=20261105");
    }

    // 결과가 없으면 빈 목록이고, 한 건이면 item이 객체로 와도 읽는다. 문자가 섞인 역 코드(NATH30000)도 받는다.
    @Test void emptyAndSingleObjectItems() {
        items("\"\"", 0);
        assertThat(client("k").trains("NATH30000", "NAT014445", DAY)).isEmpty();
        items("{\"trainno\":\"301\",\"traingradename\":\"SRT\",\"depplandtime\":\"20261105053000\",\"arrplandtime\":\"20261105080000\",\"adultcharge\":\"52900\"}", 1);
        assertThat(client("k").trains("NATH30000", "NAT014445", DAY)).singleElement().satisfies(t -> assertThat(t.minutes()).isEqualTo(150));
    }

    // 활용신청 전의 본문 없는 403과 게이트웨이 인증 오류는 키를 드러내지 않는 같은 오류 코드로 바꾼다. 잘못된 역 코드는 묻지 않는다.
    @Test void mapsGatewayErrorsWithoutLeakingKey() {
        status.set(403); body.set("");
        assertThatThrownBy(() -> client("secret-key").trains("NAT010000", "NAT014445", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> {
                assertThat(e.getCode()).isEqualTo("TRAIN_AUTH");
                assertThat(e.getMessage()).doesNotContain("secret-key");
            });
        status.set(200); body.set("{\"response\":{\"header\":{\"resultCode\":\"22\"}}}");
        assertThatThrownBy(() -> client("k").trains("NAT010000", "NAT014445", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_QUOTA"));
        assertThatThrownBy(() -> client("k").trains("NAT010000", "NAT010000", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_RESPONSE"));
        assertThatThrownBy(() -> client("k").trains("NAT010000", "서울", DAY))
            .isInstanceOfSatisfying(RecommendationException.class, e -> assertThat(e.getCode()).isEqualTo("TRAIN_RESPONSE"));
        assertThat(client("").configured()).isFalse();
    }
}
