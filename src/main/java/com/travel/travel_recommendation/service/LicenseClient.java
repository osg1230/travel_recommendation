package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.LicenseProperties;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * 행정안전부 인허가 자료(공공데이터포털)에서 음식점·숙박업의 영업 상태를 찾는다. 폐업한 곳을 일정 후보에서 빼는 데 쓴다(PlaceLicenseService).
 * 이름·주소만으로 전국에서 찾으면 30초 제한에 자주 걸려(2026-11-26 측정), 시·군·구 코드 안에서 사업장명으로만 찾는다.
 */
@Component
public class LicenseClient {
    /** 음식점은 일반음식점·휴게음식점(카페·분식 등), 숙소는 숙박업·관광숙박업(호텔·콘도) 자료에서 찾는다. 민박·펜션업 자료는 쓰지 않는다. */
    public static final List<String> RESTAURANT_SERVICES = List.of("general_restaurants", "rest_cafes"),
        STAY_SERVICES = List.of("lodgings", "tourist_accommodations");
    /** 영업상태코드. 그 밖에 02 휴업, 04 취소·말소, 05 제외·삭제, 06 기타가 있다. */
    public static final String OPEN = "01", CLOSED = "03";
    static final int ROWS = 100;
    /** 시·군·구 이름(예: "강원강릉시") → 개방자치단체코드(예: "4201000"). 공공데이터포털 참고문서 '개방자치단체코드'에서 옮겼다. */
    private static final Map<String, String> REGION_CODES = loadRegionCodes();

    /** 인허가 기록 한 건. statusCode는 영업상태코드, closedOn은 폐업일자(없으면 빈 문자열)다. */
    public record Business(String name, String roadAddress, String statusCode, String closedOn) {}

    private final LicenseProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public LicenseClient(LicenseProperties properties, ObjectMapper mapper) {
        // 느린 조회를 기다리느라 장소 목록이 늦어지지 않게 3초만 기다린다.
        this(properties, mapper, URI.create("https://apis.data.go.kr/1741000/"), Duration.ofSeconds(3));
    }

    // 실제 서버 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    LicenseClient(LicenseProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.properties = properties;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean configured() { return properties.isConfigured(); }

    /**
     * 도로명주소의 시·군·구에 해당하는 개방자치단체코드. 예) "강원특별자치도 강릉시 창해로 307" → "4201000",
     * "부산 해운대구 …" → "3330000", "경기 수원시 팔달구 …" → 수원시 코드. 찾지 못하면 비어 있다.
     */
    public static Optional<String> regionCode(String address) {
        if (address == null || address.isBlank()) return Optional.empty();
        String[] parts = Provinces.normalizeAddress(address).split(" ");
        if ("세종".equals(parts[0])) return Optional.ofNullable(REGION_CODES.get("세종"));
        if (parts.length < 2) return Optional.empty();
        // 광주 5개 구의 주소는 "전남광주 광주 동구 …"로 맞춰져 있어(Provinces) 구 이름은 세 번째 단어다.
        if (Provinces.inGwangju(address)) return Optional.ofNullable(REGION_CODES.get("광주" + parts[2]));
        // 전남·광주는 시·도 이름을 하나로 다루지만(Provinces) 코드표는 따로 적혀 있어 둘 다 찾아본다.
        for (String province : "전남광주".equals(parts[0]) ? List.of("전남", "광주") : List.of(parts[0])) {
            String code = REGION_CODES.get(province + parts[1]);
            if (code != null) return Optional.of(code);
        }
        return Optional.empty();
    }

    /**
     * 한 시·군·구(regionCode)에서 사업장명에 keyword가 들어간 인허가 기록을 앞쪽 ROWS건까지 받는다. 영업 중인 곳과 폐업한 곳이 함께 온다.
     * 같은 곳인지는 호출하는 쪽이 이름과 도로명주소로 가린다.
     */
    public List<Business> search(String service, String regionCode, String keyword) {
        if (!RESTAURANT_SERVICES.contains(service) && !STAY_SERVICES.contains(service)) throw failure("LICENSE_INPUT", "인허가 자료 종류를 확인해 주세요.");
        if (regionCode == null || !regionCode.matches("[0-9]{7}") || keyword == null || keyword.isBlank() || keyword.length() > 100)
            throw failure("LICENSE_INPUT", "인허가 자료를 찾을 지역과 이름을 확인해 주세요.");
        if (!properties.isConfigured()) throw failure("LICENSE_NOT_CONFIGURED", "지금은 영업 상태를 확인할 수 없어요.");
        // 조건 이름의 대괄호·콜론과 한글 값을 모두 인코딩해야 한다. 그대로 보내면 서버가 오류를 돌려준다(2026-11-26 확인).
        String query = "?serviceKey=" + encode(properties.serviceKey().trim()) + "&pageNo=1&numOfRows=" + ROWS + "&returnType=json"
            + "&" + encode("cond[OPN_ATMY_GRP_CD::EQ]") + "=" + regionCode
            + "&" + encode("cond[BPLC_NM::LIKE]") + "=" + encode(keyword.strip());
        try {
            // 요청 URL에는 키가 있어 로그나 예외에 남기지 않는다.
            var request = HttpRequest.newBuilder(URI.create(endpoint + service + "/info" + query)).timeout(timeout).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() != 200) throw unavailable();
            // 인증·시간 초과 오류는 "response" 없이 다른 형식으로 온다.
            var root = mapper.readTree(response.body()).path("response");
            if (!"0".equals(root.path("header").path("resultCode").asText(""))) throw unavailable();
            List<Business> result = new ArrayList<>();
            for (var item : root.path("body").path("items").path("item")) {
                String name = item.path("BPLC_NM").asText("").trim(), status = item.path("SALS_STTS_CD").asText("").trim();
                if (name.isEmpty() || !status.matches("[0-9]{2}")) continue;
                result.add(new Business(name, item.path("ROAD_NM_ADDR").asText("").trim(), status, item.path("CLSBIZ_YMD").asText("").trim()));
            }
            return List.copyOf(result);
        } catch (HttpTimeoutException e) {
            throw failure("LICENSE_TIMEOUT", "영업 상태 조회 시간이 초과됐어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("LICENSE_INTERRUPTED", "영업 상태 조회가 중단됐어요.");
        } catch (IOException e) {
            throw unavailable();
        } catch (RecommendationException e) {
            throw e;
        } catch (RuntimeException e) {
            // 예외 원인에도 키가 포함될 수 있으므로 공개 오류만 전달한다.
            throw unavailable();
        }
    }

    private static String encode(String text) { return URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20"); }

    private static Map<String, String> loadRegionCodes() {
        Map<String, String> codes = new HashMap<>();
        try (var in = LicenseClient.class.getResourceAsStream("/license/region-codes.tsv");
             var reader = new BufferedReader(new InputStreamReader(Objects.requireNonNull(in), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null; ) {
                String[] parts = line.split("\t");
                if (parts.length == 2) codes.put(parts[0], parts[1]);
            }
        } catch (IOException | RuntimeException e) {
            throw new IllegalStateException("인허가 지역 코드표(license/region-codes.tsv)를 읽지 못했습니다.", e);
        }
        return Map.copyOf(codes);
    }

    private static RecommendationException unavailable() { return failure("LICENSE_UNAVAILABLE", "영업 상태를 확인하지 못했어요."); }
    private static RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
