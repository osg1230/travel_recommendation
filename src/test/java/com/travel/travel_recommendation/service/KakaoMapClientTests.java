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
    // 요청 주소가 이 키로 시작하면 그 응답을 준다(대중교통·도보처럼 한 번에 여러 API를 부르는 경우). 없으면 body를 준다.
    private final java.util.Map<String,String> bodies=new java.util.concurrent.ConcurrentHashMap<>();
    private final KakaoMapClient.Location from=new KakaoMapClient.Location("출발",128.87,37.77,Instant.now());
    private final KakaoMapClient.Location to=new KakaoMapClient.Location("도착",128.89,37.79,Instant.now());
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{
            calls.incrementAndGet(); path.set(e.getRequestURI().toString()); auth.set(e.getRequestHeaders().getFirst("Authorization"));
            String reply=bodies.entrySet().stream().filter(b->e.getRequestURI().toString().startsWith(b.getKey()))
                .map(java.util.Map.Entry::getValue).findFirst().orElseGet(body::get);
            byte[] bytes=reply.getBytes(StandardCharsets.UTF_8);
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
    // 자동차 응답의 도로 좌표(경도, 위도 순)는 지도에 그릴 수 있게 [위도, 경도]로 보관한다. 받은 적 없는 구간은 빈 목록이다.
    @Test void carKeepsRoadPathForTheMap() {
        body.set("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":324,\"distance\":3800},\"sections\":[{\"roads\":["
            +"{\"vertexes\":[128.87,37.77,128.875,37.772]},{\"vertexes\":[128.88,37.775]}]}]}]}");
        var maps=client("key");
        maps.travelTime(from,to,KakaoMapClient.Transport.CAR);
        assertThat(maps.roadPath(from.latitude(),from.longitude(),to.latitude(),to.longitude()))
            .containsExactly(new double[]{37.77,128.87},new double[]{37.772,128.875},new double[]{37.775,128.88});
        assertThat(maps.roadPath(to.latitude(),to.longitude(),from.latitude(),from.longitude())).isEmpty();
    }
    // 점이 많은 경로는 정해 둔 개수 이하로 솎아 내되 처음과 끝은 남기고, 국내 범위를 벗어난 좌표가 섞이면 쓰지 않는다.
    @Test void roadPathIsThinnedAndRejectsOutOfRangePoints() throws Exception {
        var vertexes=new StringBuilder();
        for (int i=0;i<500;i++) vertexes.append(i==0 ? "" : ",").append(128.0+i*0.001).append(",").append(37.0+i*0.001);
        var mapper=new tools.jackson.databind.ObjectMapper();
        var thinned=KakaoMapClient.roadPath(mapper.readTree("{\"sections\":[{\"roads\":[{\"vertexes\":["+vertexes+"]}]}]}"));
        assertThat(thinned).hasSize(KakaoMapClient.PATH_MAX_POINTS);
        assertThat(thinned.getFirst()).containsExactly(37.0,128.0);
        assertThat(thinned.getLast()).containsExactly(37.499,128.499);
        assertThat(KakaoMapClient.roadPath(mapper.readTree("{\"sections\":[{\"roads\":[{\"vertexes\":[128.87,37.77,0,0]}]}]}"))).isEmpty();
    }
    @Test void transitUsesItsOwnResponseAndSelectsFastest() {
        body.set("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":1200,\"totalDistance\":4000}},{\"properties\":{\"totalTime\":1096,\"totalDistance\":3800}}]}");
        var result=client("key").travelTime(from,to,KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(result.minutes()).isEqualTo(19); assertThat(result.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(path.get()).startsWith("/v2/routing/publictraffic?");
        // 구간 정보가 없는 응답은 요약 없이 시간만 쓴다.
        assertThat(result.summary()).isNull();
    }
    @Test void transitSummaryListsRidesTransfersAndFareOfFastestRoute() {
        String bus="{\"properties\":{\"type\":\"BUS\",\"vehicles\":[{\"name\":\"서구2\"},{\"name\":\"서구2\"}],\"stops\":[{\"name\":\"감천문화마을\"},{\"name\":\"중간\"},{\"name\":\"토성역\"}]}}";
        String walk="{\"properties\":{\"type\":\"WALKING\",\"stops\":[{\"name\":\"a\"},{\"name\":\"b\"}]}}";
        String subway="{\"properties\":{\"type\":\"SUBWAY\",\"vehicles\":[{\"name\":\"1호선\"}],\"stops\":[{\"name\":\"토성\"},{\"name\":\"서면\"}]}}";
        body.set("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":2000,\"totalDistance\":9000},\"steps\":[]},"
            + "{\"properties\":{\"totalTime\":1500,\"totalDistance\":8000,\"transfers\":1,\"fare\":{\"value\":2100}},\"steps\":["+bus+","+walk+","+subway+"]}]}");
        var result=client("key").travelTime(from,to,KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(result.minutes()).isEqualTo(25);
        assertThat(result.summary()).isEqualTo("버스 서구2 (감천문화마을 → 토성역) → 지하철 1호선 (토성 → 서면) · 환승 1회 · 요금 2,100원");
        // 탑승 구간의 노선이나 정류장이 비면 틀린 안내 대신 요약을 빼고 시간만 쓴다.
        body.set("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":900,\"totalDistance\":3000},\"steps\":[{\"properties\":{\"type\":\"BUS\",\"vehicles\":[],\"stops\":[]}}]}]}");
        assertThat(client("key").travelTime(from,to,KakaoMapClient.Transport.PUBLIC_TRANSIT).summary()).isNull();
        // 자동차 경로에는 요약이 없다.
        body.set("{\"routes\":[{\"result_code\":0,\"summary\":{\"duration\":324,\"distance\":3800}}]}");
        assertThat(client("key").travelTime(from,to,KakaoMapClient.Transport.CAR).summary()).isNull();
    }
    @Test void nearbyTransitWithoutRouteBecomesMarkedWalkingEstimate() {
        // 강릉 안목해변 → 강릉카페거리(직선 약 100m). 카카오 대중교통은 NO_RESULTS로 답한다.
        var beach=new KakaoMapClient.Location("안목해변",128.948275,37.772262,Instant.now());
        var street=new KakaoMapClient.Location("카페거리",128.947242,37.772654,Instant.now());
        body.set("{\"status\":\"NO_RESULTS\"}");
        var result=client("key").travelTime(beach,street,KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(result.walkingEstimate()).isTrue();
        assertThat(result.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(result.meters()).isBetween(120,160);
        assertThat(result.minutes()).isEqualTo(2);
        assertThat(result.summary()).startsWith("도보 약 2분").contains("직선 약 101m").contains("실제 보행 경로 확인 필요");
        // 걸어갈 수 없는 거리의 NO_RESULTS와 다른 실패 상태는 추정하지 않는다.
        var far=new KakaoMapClient.Location("멀리",128.970,37.772262,Instant.now());
        assertCode("MAP_NO_ROUTE",()->client("key").travelTime(beach,far,KakaoMapClient.Transport.PUBLIC_TRANSIT));
        body.set("{\"status\":\"ERROR\"}");
        assertCode("MAP_NO_ROUTE",()->client("key").travelTime(beach,street,KakaoMapClient.Transport.PUBLIC_TRANSIT));
        // 카카오가 경로를 주면 추정값이 아니다.
        body.set("{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":300,\"totalDistance\":200}}]}");
        assertThat(client("key").travelTime(beach,street,KakaoMapClient.Transport.PUBLIC_TRANSIT).walkingEstimate()).isFalse();
    }
    // 대중교통 지도 경로(SFR-11-03): 가장 빠른 경로의 탑승·도보 단계 좌표를 [위도, 경도]로 바꾸고,
    // 응답에 없는 출발지→첫 승차, 마지막 하차→도착지는 도보 경로로 채운다. 보관하지 않으므로 부를 때마다 조회한다.
    @Test void transitPathDrawsFastestStepsAndFillsWalkingEnds() {
        String slow="{\"properties\":{\"totalTime\":2000},\"steps\":[{\"properties\":{\"type\":\"BUS\"},\"path\":{\"points\":[[128.0,37.0],[128.1,37.1]]}}]}";
        String fast="{\"properties\":{\"totalTime\":900},\"steps\":["
            +"{\"properties\":{\"type\":\"BUS\"},\"path\":{\"points\":[[128.872,37.772],[128.88,37.78]]}},"
            +"{\"properties\":{\"type\":\"WALKING\"},\"path\":{\"points\":[[128.88,37.78],[128.881,37.781]]}},"
            +"{\"properties\":{\"type\":\"SUBWAY\"},\"path\":{\"points\":[[128.881,37.781],[128.888,37.788]]}}]}";
        bodies.put("/v2/routing/publictraffic","{\"status\":\"OK\",\"routes\":["+slow+","+fast+"]}");
        bodies.put("/v2/routing/walk","{\"status\":\"OK\",\"route\":{\"legs\":[{\"steps\":["
            +"{\"path\":{\"points\":[[128.87,37.77],[128.871,37.771]]}},{\"path\":{\"points\":[[128.871,37.771],[128.872,37.772]]}}]}]}}");
        var pieces=client("key").transitPath(from,to);
        assertThat(pieces).extracting(KakaoMapClient.PathPiece::mode).containsExactly("WALK","BUS","WALK","SUBWAY","WALK");
        // 첫 도보는 도보 경로 응답의 단계를 이어 붙이고, 단계 사이에 겹치는 점은 한 번만 넣는다.
        assertThat(pieces.getFirst().points()).containsExactly(new double[]{37.77,128.87},new double[]{37.771,128.871},new double[]{37.772,128.872});
        assertThat(pieces.get(1).points()).containsExactly(new double[]{37.772,128.872},new double[]{37.78,128.88});
        // 대중교통 1번 + 처음·마지막 도보 2번. 같은 구간을 다시 그려도 다시 조회한다.
        assertThat(calls.get()).isEqualTo(3);
        client("key").transitPath(from,to);
        assertThat(calls.get()).isEqualTo(6);
    }
    @Test void nearbyLegIsDrawnAsWalkingRouteAndCloseEndsAreJoinedDirectly() {
        // 걸어갈 만큼 가까워 대중교통 경로가 없는 구간(NO_RESULTS)은 도보 경로 하나로 그린다.
        var beach=new KakaoMapClient.Location("안목해변",128.948275,37.772262,Instant.now());
        var street=new KakaoMapClient.Location("카페거리",128.947242,37.772654,Instant.now());
        bodies.put("/v2/routing/publictraffic","{\"status\":\"NO_RESULTS\"}");
        bodies.put("/v2/routing/walk","{\"status\":\"OK\",\"route\":{\"legs\":[{\"steps\":[{\"path\":{\"points\":[[128.94802,37.77207],[128.94728,37.77275]]}}]}]}}");
        var walk=client("key").transitPath(beach,street);
        assertThat(walk).extracting(KakaoMapClient.PathPiece::mode).containsExactly("WALK");
        assertThat(walk.getFirst().points()).containsExactly(new double[]{37.77207,128.94802},new double[]{37.77275,128.94728});
        // 승하차 지점이 출발·도착지에서 80m 안이면 도보 경로를 조회하지 않고 두 점을 바로 잇는다.
        calls.set(0);
        bodies.put("/v2/routing/publictraffic","{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":600},\"steps\":["
            +"{\"properties\":{\"type\":\"BUS\"},\"path\":{\"points\":[[128.8701,37.7701],[128.8899,37.7899]]}}]}]}");
        var bus=client("key").transitPath(from,to);
        assertThat(bus).extracting(KakaoMapClient.PathPiece::mode).containsExactly("WALK","BUS","WALK");
        assertThat(bus.getFirst().points()).containsExactly(new double[]{37.77,128.87},new double[]{37.7701,128.8701});
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void walkingEndsFallBackToStraightLinesAndBadPointsAreDropped() {
        // 국내 범위를 벗어난 좌표가 섞인 단계는 그리지 않고, 도보 경로를 찾지 못하면 두 점을 바로 잇는다.
        bodies.put("/v2/routing/publictraffic","{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":600},\"steps\":["
            +"{\"properties\":{\"type\":\"BUS\"},\"path\":{\"points\":[[128.872,37.772],[0,0]]}},"
            +"{\"properties\":{\"type\":\"SUBWAY\"},\"path\":{\"points\":[[128.872,37.772],[128.888,37.788]]}}]}]}");
        bodies.put("/v2/routing/walk","{\"status\":\"ROUTE_RESULT_NOT_FOUND\"}");
        var pieces=client("key").transitPath(from,to);
        assertThat(pieces).extracting(KakaoMapClient.PathPiece::mode).containsExactly("WALK","SUBWAY","WALK");
        assertThat(pieces.getFirst().points()).containsExactly(new double[]{37.77,128.87},new double[]{37.772,128.872});
        // 경로가 없거나 그릴 좌표가 하나도 없으면 MAP_NO_ROUTE다.
        bodies.put("/v2/routing/publictraffic","{\"status\":\"ERROR\"}");
        assertCode("MAP_NO_ROUTE",()->client("key").transitPath(from,to));
        bodies.put("/v2/routing/publictraffic","{\"status\":\"OK\",\"routes\":[{\"properties\":{\"totalTime\":600},\"steps\":[]}]}");
        assertCode("MAP_NO_ROUTE",()->client("key").transitPath(from,to));
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
    private java.util.Map<String,String> searchItem(String id,String address) {
        return new java.util.HashMap<>(java.util.Map.of("id",id,"place_name","장소"+id,"address_name",address,
            "road_address_name","","category_group_code","AT4","category_name","여행 > 관광,명소",
            "x","128.87","y","37.77","place_url","http://place.map.kakao.com/"+id));
    }
    @Test void searchFiltersRegionCategoryCoordinatesAndUrl() {
        var valid=searchItem("123","강원 강릉시 죽헌동 1");
        var other=searchItem("124","강원 동해시 천곡동 1");
        var wrongCategory=searchItem("125","강원 강릉시 죽헌동 2"); wrongCategory.put("category_group_code","FD6"); wrongCategory.put("category_name","음식점 > 한식");
        var badCoordinate=searchItem("126","강원 강릉시 죽헌동 3"); badCoordinate.put("x","NaN");
        var badUrl=searchItem("127","강원 강릉시 죽헌동 4"); badUrl.put("place_url","https://evil.example/127");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(valid,valid,other,wrongCategory,badCoordinate,badUrl))));
        var found=client("key").searchPlaces("강원특별자치도 강릉시","ATTRACTION");
        assertThat(found).hasSize(1); assertThat(found.getFirst().url()).isEqualTo("https://place.map.kakao.com/123");
        assertThat(found.getFirst().address()).isEqualTo("강원 강릉시 죽헌동 1");
        // 관광지는 분류 코드 대신 분류 이름으로 거른다(대표 명소는 코드가 비어 있는 경우가 많다).
        assertThat(path.get()).contains("size=15","page=1").doesNotContain("category_group_code");
    }
    // 분류 코드가 비어 있는 대표 명소도 받고, 다른 후보 안의 시설과 관광명소가 아닌 곳은 뺀다. 마지막 쪽이 아니면 3쪽까지 읽는다.
    @Test void attractionSearchKeepsUncodedLandmarksAndDropsSubPlaces() {
        var village=searchItem("401","전북특별자치도 전주시 완산구 교동 1"); village.put("category_group_code",""); village.put("place_name","전주한옥마을");
        var shrine=searchItem("402","전북특별자치도 전주시 완산구 풍남동3가 1"); shrine.put("category_group_code",""); shrine.put("place_name","경기전");
        shrine.put("category_name","여행 > 관광,명소 > 문화유적 > 사당,제단");
        var stone=searchItem("403","전북특별자치도 전주시 완산구 풍남동3가 2"); stone.put("category_group_code",""); stone.put("place_name","경기전 하마비");
        var church=searchItem("404","전북특별자치도 전주시 완산구 전동 1"); church.put("category_group_code",""); church.put("category_name","문화,예술 > 종교 > 천주교 > 성당");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("meta",java.util.Map.of("is_end",false),
            "documents",java.util.List.of(village,shrine,stone,church))));
        int before=calls.get();
        var found=client("key").searchPlaces("전북특별자치도 전주시","ATTRACTION");
        assertThat(found).extracting(com.travel.travel_recommendation.dto.KakaoPlace::name).containsExactly("전주한옥마을","경기전");
        assertThat(calls.get()-before).isEqualTo(3);
        assertThat(path.get()).contains("page=3");
    }
    // 음식점은 끼니로 쓰므로 간식·술집 분류는 받지 않는다.
    @Test void restaurantSearchSkipsSnackAndBarPlaces() {
        var meal=searchItem("701","강원 강릉시 교동 1"); meal.put("category_group_code","FD6"); meal.put("category_name","음식점 > 한식");
        var snack=searchItem("702","강원 강릉시 교동 2"); snack.put("category_group_code","FD6"); snack.put("category_name","음식점 > 간식 > 아이스크림");
        var bar=searchItem("703","강원 강릉시 교동 3"); bar.put("category_group_code","FD6"); bar.put("category_name","음식점 > 술집 > 호프,요리주점");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(snack,bar,meal))));
        assertThat(client("key").searchPlaces("강원특별자치도 강릉시","RESTAURANT")).extracting(com.travel.travel_recommendation.dto.KakaoPlace::sourceId)
            .containsExactly("701");
    }
    // 좌표 주변 음식점은 반경·정확도 순으로 찾고, 끼니가 아닌 곳과 여행지 지역 밖의 곳은 받지 않는다.
    @Test void nearbyRestaurantSearchUsesCoordinatesAndSkipsSnackAndOtherRegions() {
        var meal=searchItem("711","강원 강릉시 교동 1"); meal.put("category_group_code","FD6"); meal.put("category_name","음식점 > 한식");
        var cafe=searchItem("712","강원 강릉시 교동 2"); cafe.put("category_group_code","FD6"); cafe.put("category_name","음식점 > 카페");
        var outside=searchItem("713","강원 동해시 천곡동 3"); outside.put("category_group_code","FD6"); outside.put("category_name","음식점 > 한식");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(cafe,outside,meal))));
        assertThat(client("key").searchRestaurantsNear(128.9,37.77,"강원특별자치도 강릉시",3000,3))
            .extracting(com.travel.travel_recommendation.dto.KakaoPlace::sourceId).containsExactly("711");
        assertThat(path.get()).startsWith("/v2/local/search/category.json?category_group_code=FD6&x=128.9&y=37.77&radius=3000&sort=accuracy");
        assertThatThrownBy(()->client("key").searchRestaurantsNear(200,37.77,"강원특별자치도 강릉시",3000,3)).isInstanceOf(RecommendationException.class);
    }
    // 사찰·성당은 "○○ 명소" 검색에 나오지 않아 따로 찾는다. 사찰은 앞 5곳까지, 성당은 부속 시설과 다른 분류를 뺀다(관광지로 볼지는 장소 수집에서 정한다).
    @Test void religiousSearchKeepsTemplesAndCathedralsOnly() {
        String[][] temples={{"501","불국사"},{"502","석굴암"},{"503","골굴사"}};
        var items=new java.util.ArrayList<java.util.Map<String,String>>();
        for(var t:temples) { var item=searchItem(t[0],"경북 경주시 진현동 1"); item.put("place_name",t[1]); item.put("category_group_code","");
            item.put("category_name","문화,예술 > 종교 > 불교 > 절,사찰"); items.add(item); }
        var parking=searchItem("504","경북 경주시 진현동 2"); parking.put("category_name","교통,수송 > 교통시설 > 주차장"); items.add(0,parking);
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",items)));
        assertThat(client("key").searchTemples("경상북도 경주시")).extracting(com.travel.travel_recommendation.dto.KakaoPlace::name)
            .containsExactly("불국사","석굴암","골굴사");
        assertThat(java.net.URLDecoder.decode(path.get(),StandardCharsets.UTF_8)).contains("query=경주 사찰");

        var church=searchItem("601","전북특별자치도 전주시 완산구 전동 1"); church.put("place_name","전동성당");
        church.put("category_name","문화,예술 > 종교 > 천주교 > 성당");
        var house=new java.util.HashMap<>(church); house.put("id","602"); house.put("place_name","전동성당 사제관"); house.put("place_url","http://place.map.kakao.com/602");
        var convent=searchItem("603","전북특별자치도 전주시 완산구 전동 2"); convent.put("category_name","문화,예술 > 종교 > 수녀원,수도원");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(church,house,convent))));
        assertThat(client("key").searchCathedrals("전북특별자치도 전주시")).extracting(com.travel.travel_recommendation.dto.KakaoPlace::name)
            .containsExactly("전동성당");
    }
    // 목적 시설 전국 검색은 관광명소·문화시설만 받고, 주소가 서비스 여행지 안인 곳을 그 여행지 지역으로 돌려준다.
    @Test void nationwideSearchKeepsFacilitiesInsideServiceRegions() {
        var yeosu=searchItem("201","전남광주통합특별시 여수시 오동도로 61-11"); yeosu.put("category_group_code","CT1");
        var busan=searchItem("202","부산 해운대구 해운대해변로 266");
        var outside=searchItem("203","경기 고양시 일산서구 한류월드로 282");
        var shop=searchItem("204","부산 해운대구 구남로 1"); shop.put("category_group_code","FD6");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(yeosu,busan,outside,shop))));
        var found=client("key").searchNationwide("아쿠아리움",java.util.List.of("전남광주통합특별시 여수시","부산광역시"));
        assertThat(found).extracting(com.travel.travel_recommendation.dto.KakaoPlace::sourceId).containsExactly("201","202");
        assertThat(found).extracting(com.travel.travel_recommendation.dto.KakaoPlace::region).containsExactly("전남광주통합특별시 여수시","부산광역시");
        assertThat(found).allSatisfy(p->assertThat(p.type()).isEqualTo("ATTRACTION"));
        assertThat(path.get()).contains("size=15").doesNotContain("category_group_code");
        assertCode("MAP_INVALID_RESPONSE",()->client("key").searchNationwide(" ",java.util.List.of("부산광역시")));
    }
    // 갯벌체험장처럼 분류 코드 없이 체험여행·체험학습장으로만 분류된 곳도 목적 시설로 받는다. 축제·음식점·빌딩은 뺀다.
    @Test void nationwideSearchAcceptsUncodedExperiencePlacesOnly() {
        java.util.List<java.util.Map<String,String>> items=new java.util.ArrayList<>();
        String[][] cases={{"301","","교육,학문 > 교육단체 > 체험학습장"},{"302","","여행 > 체험여행 > 정보화,체험마을"},
            {"303","","여행 > 관광,명소"},{"304","","이슈 > 이벤트 > 페스티벌"},{"305","","부동산 > 빌딩"},
            {"306","FD6","음식점 > 한식"},{"307","AD5","여행 > 숙박 > 펜션"}};
        for(var c:cases) {
            var item=searchItem(c[0],"인천 영종구 덕교동 "+c[0]); item.put("category_group_code",c[1]); item.put("category_name",c[2]);
            items.add(item);
        }
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",items)));
        var found=client("key").searchNationwide("갯벌체험",java.util.List.of("인천광역시"));
        assertThat(found).extracting(com.travel.travel_recommendation.dto.KakaoPlace::sourceId).containsExactly("301","302","303");
        assertThat(found).allSatisfy(p->assertThat(p.region()).isEqualTo("인천광역시"));
    }
    // 대표 관광지는 이름이 같은 곳을 먼저 고른다. 사찰(종교)·시장·공원도 받아, 본체 대신 "불국사 다보탑" 같은 부속 시설로 연결되지 않게 한다.
    @Test void findLandmarkPrefersExactNameAndAcceptsTempleMarketPark() {
        var tower=searchItem("501","경북 경주시 진현동 15"); tower.put("place_name","불국사 다보탑"); tower.put("category_group_code","");
        tower.put("category_name","여행 > 관광,명소 > 문화유적 > 탑,비석");
        var temple=searchItem("502","경북 경주시 진현동 15"); temple.put("place_name","불국사"); temple.put("category_group_code","");
        temple.put("category_name","문화,예술 > 종교 > 불교 > 절,사찰");
        var parking=searchItem("503","경북 경주시 진현동 16"); parking.put("place_name","불국사 주차장"); parking.put("category_group_code","PK6");
        parking.put("category_name","교통,수송 > 교통시설 > 주차장");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(parking,tower,temple))));
        var found=client("key").findLandmark("불국사","경상북도 경주시").orElseThrow();
        assertThat(found.sourceId()).isEqualTo("502"); assertThat(found.type()).isEqualTo("ATTRACTION");
        assertThat(path.get()).contains("size=15").doesNotContain("category_group_code");
        var market=searchItem("504","부산 중구 남포동5가 105-1"); market.put("place_name","자갈치시장"); market.put("category_group_code","");
        market.put("category_name","가정,생활 > 시장");
        var fish=searchItem("505","부산 중구 남포동5가 105-2"); fish.put("place_name","자갈치시장 횟집"); fish.put("category_group_code","FD6");
        fish.put("category_name","음식점 > 한식 > 해물,생선");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(fish,market))));
        assertThat(client("key").findLandmark("자갈치시장","부산광역시").orElseThrow().sourceId()).isEqualTo("504");
    }
    // 같은 이름이 없으면 이름이 서로 포함되는 곳 중 가장 짧은 곳을 고른다. 다른 지역·관광지가 아닌 분류는 고르지 않는다.
    @Test void findLandmarkFallsBackToShortestContainingNameInsideRegion() {
        var beach=searchItem("601","강원 강릉시 강동면 정동진리 1"); beach.put("place_name","정동진 해변");
        var trail=searchItem("602","강원 강릉시 강동면 정동진리 2"); trail.put("place_name","정동진 해변 산책로 입구");
        var station=searchItem("603","강원 강릉시 강동면 정동진리 3"); station.put("place_name","정동진역"); station.put("category_group_code","");
        station.put("category_name","교통,수송 > 기차,철도 > 기차역");
        var other=searchItem("604","강원 동해시 천곡동 1"); other.put("place_name","정동진");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(trail,station,other,beach))));
        assertThat(client("key").findLandmark("정동진","강원특별자치도 강릉시").orElseThrow().sourceId()).isEqualTo("601");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(station,other))));
        assertThat(client("key").findLandmark("정동진","강원특별자치도 강릉시")).isEmpty();
        int before=calls.get();
        assertCode("MAP_INVALID_RESPONSE",()->client("key").findLandmark("가","강원특별자치도 강릉시"));
        assertThat(calls.get()).isEqualTo(before);
    }
    // 인기 음식점은 음식점(FD6) 중 끼니로 맞는 곳만, 인기 숙소는 숙박(AD5)만 이름으로 맞춘다.
    @Test void findRestaurantAndStayKeepTheirCategoriesOnly() {
        var cafe=searchItem("701","강원 강릉시 초당동 1"); cafe.put("place_name","초당순두부 카페"); cafe.put("category_group_code","FD6");
        cafe.put("category_name","음식점 > 카페");
        var tofu=searchItem("702","강원 강릉시 초당동 2"); tofu.put("place_name","초당순두부"); tofu.put("category_group_code","FD6");
        tofu.put("category_name","음식점 > 한식 > 두부전문점");
        var hotel=searchItem("703","강원 강릉시 난설헌로 1"); hotel.put("place_name","초당순두부 호텔"); hotel.put("category_group_code","AD5");
        hotel.put("category_name","여행 > 숙박 > 호텔");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(cafe,hotel,tofu))));
        var meal=client("key").findRestaurant("초당순두부","강원특별자치도 강릉시").orElseThrow();
        assertThat(meal.sourceId()).isEqualTo("702"); assertThat(meal.type()).isEqualTo("RESTAURANT");
        var stay=client("key").findStay("초당순두부 호텔","강원특별자치도 강릉시").orElseThrow();
        assertThat(stay.sourceId()).isEqualTo("703"); assertThat(stay.type()).isEqualTo("ACCOMMODATION");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(cafe))));
        assertThat(client("key").findRestaurant("초당순두부","강원특별자치도 강릉시")).isEmpty();
    }
    // 관광공사에서 찾은 시설은 좌표 1km 안에서 이름이 맞는 가장 가까운 목적 시설로 맞춘다. 주차장·다른 이름은 고르지 않는다.
    @Test void findNearMatchesNameAndCategoryNearTheCoordinate() {
        var parking=searchItem("401","강원 강릉시 초당동 505"); parking.put("place_name","경포아쿠아리움 주차장"); parking.put("category_group_code","PK6");
        var other=searchItem("402","강원 강릉시 초당동 505"); other.put("place_name","경포석호생태관"); other.put("category_group_code","CT1");
        var aquarium=searchItem("403","강원 강릉시 초당동 505"); aquarium.put("place_name","경포아쿠아리움"); aquarium.put("category_group_code","CT1");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(parking,other,aquarium))));
        var found=client("key").findNear("경포 아쿠아리움",128.9079,37.79,"강원특별자치도 강릉시").orElseThrow();
        assertThat(found.sourceId()).isEqualTo("403"); assertThat(found.region()).isEqualTo("강원특별자치도 강릉시");
        assertThat(path.get()).contains("x=128.9079","y=37.79","radius=1000","sort=distance");
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",java.util.List.of(parking,other))));
        assertThat(client("key").findNear("경포 아쿠아리움",128.9079,37.79,"강원특별자치도 강릉시")).isEmpty();
        int before=calls.get();
        assertCode("MAP_INVALID_RESPONSE",()->client("key").findNear("경포 아쿠아리움",0,0,"강원특별자치도 강릉시"));
        assertThat(calls.get()).isEqualTo(before);
    }
    @Test void searchHasBoundedResultsAndRejectsUnsupportedRegionBeforeCalling() {
        var items=new java.util.ArrayList<java.util.Map<String,String>>();
        for(int i=1;i<=15;i++) items.add(searchItem(""+i,"부산 해운대구 중동 "+i));
        body.set(new JsonMapper().writeValueAsString(java.util.Map.of("documents",items)));
        assertThat(client("key").searchPlaces("부산광역시","ATTRACTION")).hasSize(15);
        int before=calls.get(); assertCode("MAP_REGION",()->client("key").searchPlaces("어딘가 어느곳 모름","ATTRACTION"));
        assertThat(calls.get()).isEqualTo(before);
        body.set("{\"documents\":[]}"); assertThat(client("key").searchPlaces("부산광역시","ATTRACTION")).isEmpty();
        body.set("{}"); assertCode("MAP_INVALID_RESPONSE",()->client("key").searchPlaces("부산광역시","ATTRACTION"));
    }
}
