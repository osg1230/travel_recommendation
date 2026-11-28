package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.LicenseProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class LicenseClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>(), path = new AtomicReference<>(), query = new AtomicReference<>();
    private final AtomicInteger status = new AtomicInteger(200), calls = new AtomicInteger();
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
    private LicenseClient client(String key) {
        return new LicenseClient(new LicenseProperties(key), new JsonMapper(),
            URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"), Duration.ofSeconds(1));
    }

    // 2026-11-26 실제 응답과 같은 형식. 영업 중인 곳과 폐업한 곳이 함께 온다.
    @Test void readsBusinessesAndEncodesConditionNames() {
        body.set("{\"response\":{\"body\":{\"dataType\":\"JSON\",\"items\":{\"item\":["
            + "{\"BPLC_NM\":\"파스타예요 강릉초당점\",\"ROAD_NM_ADDR\":\"강원특별자치도 강릉시 난설헌로 201 (초당동)\",\"SALS_STTS_CD\":\"03\",\"SALS_STTS_NM\":\"폐업\",\"CLSBIZ_YMD\":\"2026-11-03\"},"
            + "{\"BPLC_NM\":\"초당할머니순두부\",\"ROAD_NM_ADDR\":\"강원특별자치도 강릉시 초당순두부길 77 (초당동)\",\"SALS_STTS_CD\":\"01\",\"SALS_STTS_NM\":\"영업/정상\",\"CLSBIZ_YMD\":\"\"},"
            + "{\"BPLC_NM\":\"\",\"SALS_STTS_CD\":\"01\"}]},"
            + "\"numOfRows\":100,\"pageNo\":1,\"totalCount\":3},\"header\":{\"resultCode\":\"0\",\"resultMsg\":\"정상\"}}}");
        var found = client("a+b/c=").search("general_restaurants", "4201000", "초당 순두부");
        assertThat(found).containsExactly(
            new LicenseClient.Business("파스타예요 강릉초당점", "강원특별자치도 강릉시 난설헌로 201 (초당동)", "03", "2026-11-03"),
            new LicenseClient.Business("초당할머니순두부", "강원특별자치도 강릉시 초당순두부길 77 (초당동)", "01", ""));
        assertThat(path.get()).isEqualTo("/general_restaurants/info");
        // 조건 이름의 대괄호·콜론과 한글·공백을 모두 인코딩한다.
        assertThat(query.get()).contains("serviceKey=a%2Bb%2Fc%3D", "numOfRows=100", "returnType=json",
            "cond%5BOPN_ATMY_GRP_CD%3A%3AEQ%5D=4201000", "cond%5BBPLC_NM%3A%3ALIKE%5D=%EC%B4%88%EB%8B%B9%20%EC%88%9C%EB%91%90%EB%B6%80");
        assertThat(new LicenseProperties("a+b/c=").toString()).doesNotContain("a+b/c=");
    }

    @Test void emptyResultIsAnEmptyList() {
        body.set("{\"response\":{\"body\":{\"items\":null,\"totalCount\":0},\"header\":{\"resultCode\":\"0\"}}}");
        assertThat(client("key").search("lodgings", "4201000", "없는숙소")).isEmpty();
    }

    // 인증 오류·시간 초과는 "response" 없이 다른 형식이나 504로 온다. 폐업 여부를 추측하지 않도록 실패로 알린다.
    @Test void gatewayErrorsAndTimeoutsFail() {
        body.set("{\"OpenAPI_ServiceResponse\":{\"cmmMsgHeader\":{\"errMsg\":\"HTTP_ERROR\",\"returnReasonCode\":\"04\"}}}");
        assertThatThrownBy(() -> client("key").search("rest_cafes", "4201000", "테라로사"))
            .isInstanceOf(RecommendationException.class).extracting("code").isEqualTo("LICENSE_UNAVAILABLE");
        status.set(504);
        assertThatThrownBy(() -> client("key").search("rest_cafes", "4201000", "테라로사"))
            .isInstanceOf(RecommendationException.class).extracting("code").isEqualTo("LICENSE_UNAVAILABLE");
    }

    @Test void rejectsBadInputAndMissingKeyWithoutCalling() {
        body.set("{}");
        assertThatThrownBy(() -> client("key").search("unknown", "4201000", "가게")).isInstanceOf(RecommendationException.class);
        assertThatThrownBy(() -> client("key").search("lodgings", "강릉", "가게")).isInstanceOf(RecommendationException.class);
        assertThatThrownBy(() -> client("key").search("lodgings", "4201000", " ")).isInstanceOf(RecommendationException.class);
        assertThatThrownBy(() -> client("").search("lodgings", "4201000", "가게"))
            .isInstanceOf(RecommendationException.class).extracting("code").isEqualTo("LICENSE_NOT_CONFIGURED");
        assertThat(calls.get()).isZero();
        assertThat(client("").configured()).isFalse();
    }

    // 주소의 시·군·구로 코드를 찾는다. 카카오의 짧은 시·도 표기와 일반구가 있는 시, 세종도 찾는다.
    @Test void findsRegionCodeFromRoadAddress() {
        assertThat(LicenseClient.regionCode("강원특별자치도 강릉시 창해로 307")).contains("4201000");
        assertThat(LicenseClient.regionCode("강원 강릉시 창해로 307")).contains("4201000");
        assertThat(LicenseClient.regionCode("부산 해운대구 달맞이길 30")).contains("3330000");
        assertThat(LicenseClient.regionCode("경기 수원시 팔달구 정조로 825")).contains("3740000");
        assertThat(LicenseClient.regionCode("제주특별자치도 서귀포시 중문관광로 29")).contains("6520000");
        assertThat(LicenseClient.regionCode("세종특별자치시 한누리대로 2130")).contains("5690000");
        assertThat(LicenseClient.regionCode("전남 여수시 돌산읍 돌산로 3600")).contains("4810000");
        assertThat(LicenseClient.regionCode("광주 동구 금남로 1")).contains("3590000");
        assertThat(LicenseClient.regionCode("경기 광주시 남한산성면")).contains("5540000");
        assertThat(LicenseClient.regionCode("어딘가 없는시 길 1")).isEmpty();
        assertThat(LicenseClient.regionCode("강원")).isEmpty();
        assertThat(LicenseClient.regionCode(null)).isEmpty();
    }
}
