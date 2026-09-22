package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class PricePageCheckerTests {
    private static final String ADDRESS = "강원특별자치도 강릉시 초당순두부길 47";
    private HttpServer server;
    private final AtomicInteger status = new AtomicInteger(200);
    private final AtomicReference<String> page = new AtomicReference<>();

    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", e -> {
            byte[] bytes = page.get().getBytes(StandardCharsets.UTF_8);
            if (status.get() == 302) e.getResponseHeaders().add("Location", "http://127.0.0.1:1/other");
            e.sendResponseHeaders(status.get(), bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/menu"; }

    @Test void confirmsOnlyWhenNameAndPriceAreOnThePage() {
        var checker = new PricePageChecker(true);
        page.set("<html><h1>토담 순두부</h1><p>강릉시 초당순두부길 47</p><script>var x='99,000';</script><li>순두부백반 <b>11,000</b>원</li></html>");
        // 띄어쓰기가 달라도 같은 상호로 보고, 스크립트 안 숫자는 가격으로 보지 않는다.
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 11000, 11000)).isTrue();
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 99000, 99000)).isFalse();
        assertThat(checker.confirms(url(), "초당할머니순두부", ADDRESS, 11000, 11000)).isFalse();
        page.set("<p>토담순두부 초당순두부길 가격 13000원</p>");
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 11000, 13000)).isTrue();
        // 상호가 메뉴 이름으로만 나오는 다른 가게 페이지(도로명이 다름)는 확인으로 보지 않는다.
        page.set("<p>원조교동반점 · 강릉시 교동 · 교동짬뽕 12,000원</p>");
        assertThat(checker.confirms(url(), "교동짬뽕", "강원특별자치도 강릉시 강릉대로 205", 12000, 12000)).isFalse();
        assertThat(PricePageChecker.roadName("강원특별자치도 강릉시 해안로406번길 2")).isEqualTo("해안로406번길");
        assertThat(PricePageChecker.roadName("강원특별자치도 양양군 현북면 하광정리")).isNull();
        page.set("<p>토담순두부 초당순두부길 가격 13000원</p>");
        // 다른 곳으로 이동시키는 응답과 오류 응답은 따라가지 않는다.
        status.set(302);
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 13000, 13000)).isFalse();
        status.set(500);
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 13000, 13000)).isFalse();
    }

    // 금액은 다른 숫자의 일부로 맞추지 않는다. 110,000원이 10,000원의 근거가 되면 안 된다.
    @Test void priceMustBeAWholeAmountNotPartOfAnotherNumber() {
        assertThat(PricePageChecker.pricesIn("객실110,000원", 10000, 10000)).isFalse();
        assertThat(PricePageChecker.pricesIn("객실10,000,000원", 10000, 10000)).isFalse();
        assertThat(PricePageChecker.pricesIn("객실1,110,000원", 110000, 110000)).isFalse();
        assertThat(PricePageChecker.pricesIn("정식110000원", 10000, 10000)).isFalse();
        assertThat(PricePageChecker.pricesIn("정식100005원", 10000, 10000)).isFalse();
        assertThat(PricePageChecker.pricesIn("객실110,000원·조식10,000원", 10000, 10000)).isTrue();
        assertThat(PricePageChecker.pricesIn("순두부,11,000원", 11000, 11000)).isTrue();
        assertThat(PricePageChecker.pricesIn("가격:11000원부터", 11000, 11000)).isTrue();
        assertThat(PricePageChecker.pricesIn("1박120,000~150,000원", 100000, 150000)).isTrue();
    }

    // AI가 준 주소로 서버가 요청하므로 https 공개 주소가 아니면 열지 않는다.
    @Test void refusesLocalPrivateAndNonHttpsAddresses() {
        page.set("<p>토담순두부 11,000원</p>");
        var checker = new PricePageChecker();
        assertThat(checker.confirms(url(), "토담순두부", ADDRESS, 11000, 11000)).isFalse();
        for (String address : new String[] {"https://127.0.0.1/", "https://localhost/", "https://10.0.0.5/", "https://192.168.0.2/",
                "https://169.254.169.254/latest", "https://user@example.com/", "https://example.com:8443/", "ftp://example.com/"})
            assertThat(checker.confirms(address, "토담순두부", ADDRESS, 11000, 11000)).as(address).isFalse();
    }
}
