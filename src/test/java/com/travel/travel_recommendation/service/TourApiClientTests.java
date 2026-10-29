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
        response("12", ",\"usetime\":\"09:00~18:00\",\"restdate\":\"매주 월요일\",\"parking\":\"가능\"");
        var result = client("a+b/c=").findOperatingInfo("123", "12").orElseThrow();
        assertThat(result.hours()).isEqualTo("09:00~18:00");
        assertThat(result.closedDays()).isEqualTo("매주 월요일");
        assertThat(result.parking()).isEqualTo("가능");
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
        // 주차는 유형별 필드에서 읽고, 문화시설·레포츠는 요금을 함께 적는다. 둘 다 없으면 빈 값이다.
        response("14", ",\"parkingculture\":\"가능\",\"parkingfee\":\"무료\"");
        assertThat(client("key").findOperatingInfo("123", "14").orElseThrow().parking()).isEqualTo("가능 · 요금 무료");
        response("28", ",\"parkingfeeleports\":\"유료\"");
        assertThat(client("key").findOperatingInfo("123", "28").orElseThrow().parking()).isEqualTo("요금 유료");
        response("39", ",\"parkingfood\":\"가능\"");
        assertThat(client("key").findOperatingInfo("123", "39").orElseThrow().parking()).isEqualTo("가능");
        response("32", ",\"parkinglodging\":\"있음\"");
        assertThat(client("key").findOperatingInfo("123", "32").orElseThrow().parking()).isEqualTo("있음");
        response("14", "");
        assertThat(client("key").findOperatingInfo("123", "14").orElseThrow().parking()).isEmpty();
    }
    @Test void rejectsMissingKeyAndBadIdsWithoutHttpCalls() {
        assertCode("TOUR_NOT_CONFIGURED", () -> client("").findOperatingInfo("123", "12"));
        assertCode("TOUR_INPUT", () -> client("key").findOperatingInfo("123&x=1", "12"));
        assertCode("TOUR_INPUT", () -> client("key").findOperatingInfo("123", "15"));
        assertThat(calls.get()).isZero();
    }
    @Test void searchesOnlyCompleteBoundedResultSets() {
        response("14", ",\"title\":\"오죽헌\",\"addr1\":\"강원 강릉시 율곡로3139번길 24\",\"mapx\":\"128.8796\",\"mapy\":\"37.7791\"");
        assertThat(client("key").search("오죽헌")).hasSize(1);
        assertThat(query.get()).contains("keyword=", "numOfRows=10");
        body.set(body.get().replace("\"totalCount\":1", "\"totalCount\":11"));
        assertCode("TOUR_RESPONSE", () -> client("key").search("오죽헌"));
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":0,\"items\":\"\"}}}");
        assertThat(client("key").search("없는 장소")).isEmpty();
    }
    // 좌표는 제공자가 문자열로도 숫자로도 보낸다. 읽지 못한 결과만 빠지고 검색 전체가 실패하지 않는다.
    @Test void readsCoordinatesAsTextOrNumberAndSkipsUnusableResults() {
        String place = ",\"title\":\"오죽헌\",\"addr1\":\"강원 강릉시 율곡로3139번길 24\"";
        response("14", place + ",\"mapx\":128.8796,\"mapy\":37.7791");
        assertThat(client("key").search("오죽헌")).singleElement()
            .satisfies(found -> assertThat(found.longitude()).isEqualTo(128.8796));
        response("14", place + ",\"mapx\":\" 128.8796 \",\"mapy\":\"37.7791\"");
        assertThat(client("key").search("오죽헌")).hasSize(1);
        response("14", place);
        assertThat(client("key").search("오죽헌")).isEmpty();
        response("14", place + ",\"mapx\":\"좌표없음\",\"mapy\":\"37.7791\"");
        assertThat(client("key").search("오죽헌")).isEmpty();
        response("14", place + ",\"mapx\":139.7,\"mapy\":35.7");
        assertThat(client("key").search("오죽헌")).isEmpty();
    }
    // 목적 시설 검색은 관광지·문화시설만 받고 음식점·숙박·축제는 뺀다. 100건까지 한 번에 받는다.
    @Test void facilitySearchKeepsAttractionsAndCulturalFacilitiesOnly() {
        String item = "{\"contentid\":\"%s\",\"contenttypeid\":\"%s\",\"title\":\"%s\",\"addr1\":\"인천광역시 영종구 마시란로 107-8\",\"mapx\":\"126.417\",\"mapy\":\"37.431\"}";
        var items = String.join(",", item.formatted("1", "12", "마시안갯벌체험장"), item.formatted("2", "14", "갯벌센터"),
            item.formatted("3", "39", "갯벌낙지"), item.formatted("4", "32", "갯벌펜션"), item.formatted("5", "15", "갯벌축제"),
            "{\"contentid\":\"6\",\"contenttypeid\":\"12\",\"title\":\"좌표없음\",\"addr1\":\"인천광역시\"}");
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":6,\"items\":{\"item\":[" + items + "]}}}}");
        var found = client("key").searchFacilities("갯벌");
        assertThat(found).extracting(TourApiClient.SearchPlace::id).containsExactly("1", "2");
        assertThat(found.getFirst().name()).isEqualTo("마시안갯벌체험장");
        assertThat(query.get()).contains("numOfRows=100", "keyword=%EA%B0%AF%EB%B2%8C");
        // 전체 수보다 적게 온 응답은 잘린 응답으로 보고 쓰지 않는다.
        body.set(body.get().replace("\"totalCount\":6", "\"totalCount\":7"));
        assertCode("TOUR_RESPONSE", () -> client("key").searchFacilities("갯벌"));
        int before = calls.get();
        assertCode("TOUR_INPUT", () -> client("key").searchFacilities(" "));
        assertThat(calls.get()).isEqualTo(before);
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
    @Test void festivalSearchReadsDatesAndSkipsBrokenOrOtherRegionItems() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":4,\"items\":{\"item\":["
            + "{\"contentid\":\"900168\",\"title\":\"보은대추 축제\",\"eventstartdate\":\"20261016\",\"eventenddate\":\"20261025\",\"lDongRegnCd\":\"43\","
            + "\"addr1\":\"충청북도 보은군\",\"addr2\":\"뱃들공원 일원\",\"firstimage\":\"https://tong.visitkorea.or.kr/a.jpg\",\"cpyrhtDivCd\":\"Type3\",\"tel\":\"043-540-3392\"},"
            + "{\"contentid\":\"2\",\"title\":\"사진 제한\",\"eventstartdate\":\"20261001\",\"eventenddate\":\"20261001\",\"lDongRegnCd\":\"43\",\"firstimage\":\"https://x/b.jpg\",\"cpyrhtDivCd\":\"Type2\"},"
            + "{\"contentid\":\"3\",\"title\":\"날짜 오류\",\"eventstartdate\":\"2026-10-01\",\"eventenddate\":\"20261001\",\"lDongRegnCd\":\"43\"},"
            + "{\"contentid\":\"4\",\"title\":\"다른 도\",\"eventstartdate\":\"20261001\",\"eventenddate\":\"20261001\",\"lDongRegnCd\":\"44\"}]}}}}");
        var result = client("key").searchFestivals("43", java.time.LocalDate.of(2026, 9, 27));
        assertThat(query.get()).contains("lDongRegnCd=43", "eventStartDate=20260927");
        assertThat(result).extracting(f -> f.title()).containsExactly("보은대추 축제", "사진 제한");
        assertThat(result.get(0).period()).isEqualTo("10.16(금) ~ 10.25(일)");
        assertThat(result.get(0).place()).isEqualTo("뱃들공원 일원");
        assertThat(result.get(1).image()).isEmpty();
        assertCode("TOUR_INPUT", () -> client("key").searchFestivals("43&x=1", java.time.LocalDate.of(2026, 9, 27)));
    }
    // 반려동물 동반 정보는 원문 그대로 받고, 등록되지 않은 장소는 비어 있으며, 다른 콘텐츠 ID가 오면 쓰지 않는다.
    @Test void readsPetTourInfoAndKeepsUnregisteredPlacesEmpty() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":1,\"items\":{\"item\":[{\"contentid\":\"129784\","
            + "\"acmpyTypeCd\":\"전구역 동반가능\",\"acmpyPsblCpam\":\"전 견종 동반 가능\",\"acmpyNeedMtr\":\"목줄 착용\",\"etcAcmpyInfo\":\"배변봉투 지참\"}]}}}}");
        var info = client("key").findPetInfo("129784").orElseThrow();
        assertThat(info.allowedArea()).isEqualTo("전구역 동반가능");
        assertThat(info.animals()).isEqualTo("전 견종 동반 가능");
        assertThat(info.requirements()).isEqualTo("목줄 착용");
        assertThat(info.etc()).isEqualTo("배변봉투 지참");
        assertThat(info.status()).isEqualTo("ALLOWED");
        assertThat(query.get()).contains("contentId=129784", "numOfRows=1");
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"items\":\"\",\"numOfRows\":0,\"pageNo\":1,\"totalCount\":0}}}");
        assertThat(client("key").findPetInfo("129784")).isEmpty();
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":1,\"items\":{\"item\":[{\"contentid\":\"999\",\"acmpyTypeCd\":\"전구역 동반가능\"}]}}}}");
        assertCode("TOUR_RESPONSE", () -> client("key").findPetInfo("129784"));
        assertCode("TOUR_INPUT", () -> client("key").findPetInfo("abc"));
    }
    // 연관 관광지는 시·군·구 코드 앞 두 자리를 시·도 코드로 보내고, 순위가 숫자가 아닌 행만 버린다. 전체 행이 한 쪽 안이면 다음 쪽을 부르지 않는다.
    @Test void relatedPlacesSendsDistrictCodesAndSkipsBadRows() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":2,\"items\":{\"item\":["
            + "{\"tAtsNm\":\"경포대\",\"rlteTatsNm\":\"강릉중앙시장\",\"rlteSignguCd\":\"51150\",\"rlteCtgryLclsNm\":\"관광지\",\"rlteCtgryMclsNm\":\"쇼핑\",\"rlteCtgrySclsNm\":\"시장\",\"rlteRank\":\"4\"},"
            + "{\"tAtsNm\":\"경포대\",\"rlteTatsNm\":\"순위 없음\",\"rlteSignguCd\":\"51150\",\"rlteCtgryLclsNm\":\"관광지\",\"rlteCtgryMclsNm\":\"쇼핑\",\"rlteCtgrySclsNm\":\"시장\",\"rlteRank\":\"x\"}]}}}}");
        var rows = client("key").relatedPlaces("51150", java.time.YearMonth.of(2026, 9), 3);
        assertThat(rows).containsExactly(new TourApiClient.RelatedPlace("경포대", "강릉중앙시장", "51150", "관광지", "쇼핑", "시장", 4));
        assertThat(query.get()).contains("baseYm=202609", "areaCd=51", "signguCd=51150", "pageNo=1", "numOfRows=1000");
        assertThat(calls.get()).isEqualTo(1);
        assertCode("TOUR_INPUT", () -> client("key").relatedPlaces("150", java.time.YearMonth.of(2026, 9), 1));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void districtCodesListsThreeDigitCodesInsideProvince() {
        body.set("{\"response\":{\"header\":{\"resultCode\":\"0000\"},\"body\":{\"totalCount\":3,\"items\":{\"item\":["
            + "{\"code\":\"110\",\"name\":\"중구\"},{\"code\":\"140\",\"name\":\"서구\"},{\"code\":\"x\",\"name\":\"잘못된 코드\"}]}}}}");
        assertThat(client("key").districtCodes("26")).containsExactly("110", "140");
        assertThat(query.get()).contains("lDongRegnCd=26");
        int before = calls.get();
        assertThat(client("key").districtCodes("2")).isEmpty();
        assertThat(calls.get()).isEqualTo(before);
    }
}
