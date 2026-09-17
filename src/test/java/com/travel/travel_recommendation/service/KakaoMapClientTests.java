package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.KakaoMapProperties;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class KakaoMapClientTests {
    private HttpServer server;
    private final AtomicReference<String> body=new AtomicReference<>(), path=new AtomicReference<>(), auth=new AtomicReference<>();
    private final AtomicInteger status=new AtomicInteger(200), calls=new AtomicInteger();
    private final KakaoMapClient.Location from=new KakaoMapClient.Location("출발",128.87,37.77,Instant.now());
    private final KakaoMapClient.Location to=new KakaoMapClient.Location("도착",128.89,37.79,Instant.now());
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            calls.incrementAndGet(); path.set(e.getRequestURI().toString()); auth.set(e.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(),bytes.length); e.getResponseBody().write(bytes); e.close();
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private KakaoMapClient client(String key) {
        var endpoint=URI.create("http://127.0.0.1:"+server.getAddress().getPort());
        return new KakaoMapClient(new KakaoMapProperties(key),new JsonMapper(),endpoint,endpoint,Duration.ofSeconds(2));
    }
    private void address(String name,String longitude) {
        body.set("{\"documents\":[{\"road_address\":{\"address_name\":\""+name+"\",\"x\":\""+longitude+"\",\"y\":\"37.7783\"}}]}");
    }
    @Test void verifiesAddressAndCoordinateOrder() {
        address("강원 강릉시 율곡로3139번길 24","128.8767");
        var result=client("test-key").locate("강원특별자치도 강릉시 율곡로3139번길 24");
        assertThat(result.longitude()).isEqualTo(128.8767); assertThat(result.latitude()).isEqualTo(37.7783);
        assertThat(auth.get()).isEqualTo("KakaoAK test-key"); assertThat(path.get()).doesNotContain("test-key");
    }
    @Test void rejectsDifferentAddressMissingAmbiguousAndInvalidCoordinates() {
        address("강원 강릉시 경포로 365","128.8");
        assertCode("MAP_ADDRESS",()->client("key").locate("강원 강릉시 경포로 366"));
        for (String value:new String[]{"NaN","Infinity","37.7","999"}) {
            address("강원 강릉시 경포로 365",value);
            assertCode("MAP_ADDRESS",()->client("key").locate("강원 강릉시 경포로 365"));
        }
        for (String value:new String[]{"{}","{\"documents\":[]}","{\"documents\":[{},{}]}"}) {
            body.set(value); assertCode("MAP_ADDRESS",()->client("key").locate("강원 강릉시 경포로 365"));
        }
    }
    @Test void carUsesSecondsAndRoundsUp() {
        body.set("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":324,\"distance\":3800}}]}");
        var result=client("key").travelTime(from,to,KakaoMapClient.Transport.CAR);
        assertThat(result.minutes()).isEqualTo(6); assertThat(result.meters()).isEqualTo(3800);
        assertThat(path.get()).startsWith("/v1/directions?origin=128.87,37.77");
    }
    @Test void transitUsesItsOwnResponseAndSelectsFastest() {
        body.set("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":1200,\"totalDistance\":4000}},{\"properties\":{\"totalTime\":1096,\"totalDistance\":3800}}]}");
        var result=client("key").travelTime(from,to,KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(result.minutes()).isEqualTo(19); assertThat(result.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(path.get()).startsWith("/v2/routing/publictraffic?");
    }
    @Test void noRouteAndMalformedDurationsNeverBecomeEstimatedTimes() {
        body.set("{\"status\":\"NO_RESULTS\"}");
        assertCode("MAP_NO_ROUTE",()->client("key").travelTime(from,to,KakaoMapClient.Transport.PUBLIC_TRANSIT));
        for (String seconds:new String[]{"0","-1","1.5","\"60\"","999999999999"}) {
            body.set("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":"+seconds+",\"distance\":3800}}]}");
            assertCode("MAP_INVALID_RESPONSE",()->client("key").travelTime(from,to,KakaoMapClient.Transport.CAR));
        }
        assertCode("MAP_SAME_LOCATION",()->client("key").travelTime(from,from,KakaoMapClient.Transport.CAR));
    }
    @Test void authenticationErrorsAreSafeAndNeverRetried() {
        body.set("private-provider-message test-key");
        for (int code:new int[]{401,403,429,500}) {
            status.set(code);
            assertThatThrownBy(()->client("test-key").locate("주소"))
                .isInstanceOf(RecommendationException.class).hasMessageNotContaining("private-provider-message").hasMessageNotContaining("test-key");
        }
        assertThat(calls.get()).isEqualTo(4);
        assertCode("MAP_NOT_CONFIGURED",()->client("").locate("주소"));
        assertThat(calls.get()).isEqualTo(4);
        assertThat(new KakaoMapProperties("test-key").toString()).doesNotContain("test-key");
    }
    private void assertCode(String code,Runnable action) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo(code));
    }
}
