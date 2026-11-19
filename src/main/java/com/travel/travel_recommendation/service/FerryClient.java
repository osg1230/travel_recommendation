package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TagoProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/**
 * 국토교통부 TAGO 국내선박운항정보. 출발 항구의 하루 운항편(도착지·출발·도착 예정 시각·선박명)을 조회한다.
 * 항공 운항 정보와 같은 공공데이터포털 계정 키를 쓰며 활용신청은 따로 해야 한다. 2026-10-30 확인 결과 오늘 운항편만 있고
 * 2주 뒤 날짜는 0건으로 온다. 요청·응답 처리 방식은 FlightClient와 같다.
 */
@Component
public class FerryClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(FerryClient.class);
    /** 키가 없거나 인증에 실패했을 때 사용자에게 보여 주는 문장. 사용자가 고칠 수 없는 원인은 로그에만 남긴다. */
    private static final String UNAVAILABLE = "지금은 여객선 운항 정보를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.";
    /** 운항편 한 건. arrivalPort는 TAGO 항구 이름(예: 울릉_도동)이고, 시각은 운항 정보에 알린 예정 시각이다. */
    public record Sailing(String departurePort, String arrivalPort, LocalDateTime departure, LocalDateTime arrival, String vessel) {
        public int minutes() { return (int) Duration.between(departure, arrival).toMinutes(); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    static final int PAGE_SIZE = 100, MAX_PAGES = 3;
    /** 이보다 짧은 편은 도착 시각이 잘못 들어온 값으로 본다(예: 포항영일만 → 울릉 사동 23:00 → 23:03). */
    static final int MIN_MINUTES = 10;
    private static final Duration TAGO_CACHE_TTL = IntercityBusClient.TAGO_CACHE_TTL;
    private final TagoProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public FerryClient(TagoProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create("https://apis.data.go.kr/1613000/DmstcShipNvgInfo/"), Duration.ofSeconds(8));
    }
    // 실제 서버 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    FerryClient(TagoProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.properties = properties; this.mapper = mapper; this.endpoint = endpoint; this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean configured() { return properties.isConfigured(); }

    /** 같은 구간·날짜를 TAGO_CACHE_TTL 동안 다시 부르지 않는다. TAGO는 당일 배차·운항 자료라 그 사이 결과가 같고, 버스 안내 한 번에 수십 번 부르던 호출을 줄인다. 실패는 보관하지 않는다. */
    private final ExpiringCache<String, List<Sailing>> sailingsCache = new ExpiringCache<>(TAGO_CACHE_TTL, 500);

    /** date에 출발 항구에서 떠나는 운항편. 출발 시각 순서이며 없으면 빈 목록이다. 항구 ID는 TAGO 형식(예: SEA43010)이다. */
    public List<Sailing> sailings(String departurePortId, LocalDate date) {
        return sailingsCache.get(departurePortId+":"+date, () -> loadSailings(departurePortId, date));
    }

    private List<Sailing> loadSailings(String departurePortId, LocalDate date) {
        if (departurePortId == null || date == null || !departurePortId.matches("SEA[0-9]{5}")) throw invalid();
        List<Sailing> result = new ArrayList<>();
        int read = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            var body = get("GetShipOpratInfoList", "&pageNo=" + page + "&numOfRows=" + PAGE_SIZE + "&depNodeId=" + departurePortId
                + "&depPlandTime=" + date.format(DATE));
            var items = items(body);
            for (var item : items) {
                var departure = time(item, "depPlandTime");
                var arrival = time(item, "arrPlandTime");
                // 날짜가 다르거나, 도착이 출발 10분 안이거나, 12시간을 넘는 편은 잘못된 값으로 보고 쓰지 않는다.
                if (departure == null || arrival == null || !departure.toLocalDate().equals(date)
                        || arrival.isBefore(departure.plusMinutes(MIN_MINUTES)) || arrival.isAfter(departure.plusHours(12))) continue;
                String to = text(item, "arrPlaceNm");
                if (to.isEmpty()) continue;
                result.add(new Sailing(text(item, "depPlaceNm"), to, departure, arrival, text(item, "vihicleNm")));
            }
            read += items.size();
            int total = body.path("totalCount").asInt(read);
            if (items.isEmpty() || read >= total) break;
            if (page == MAX_PAGES) throw failure("FERRY_TOO_MANY", "여객선 운항편이 너무 많아 요약하지 못했어요.");
        }
        result.sort(Comparator.comparing(Sailing::departure));
        return List.copyOf(result);
    }

    private JsonNode get(String operation, String parameters) {
        if (!properties.isConfigured()) throw failure("FERRY_NOT_CONFIGURED", UNAVAILABLE);
        // Decoding 키를 한 번만 인코딩한다. 요청 URL에는 키가 있어 로그에 남기지 않는다.
        String query = "?serviceKey=" + URLEncoder.encode(properties.serviceKey().trim(), StandardCharsets.UTF_8) + "&_type=json" + parameters;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint.resolve(operation) + query)).timeout(timeout).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            // 활용신청 전에는 본문 없는 403이 온다. 게이트웨이 인증 오류는 JSON·XML 어느 쪽일 수도 있어 원문은 반환하지 않는다.
            if (body.contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR") || body.contains("SERVICE_ACCESS_DENIED_ERROR")
                    || body.contains("DEADLINE_HAS_EXPIRED_ERROR") || body.contains("SERVICE_KEY_IS_NULL")
                    || response.statusCode() == 401 || response.statusCode() == 403) throw auth();
            if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") || response.statusCode() == 429) throw quota();
            if (response.statusCode() != 200 || body.stripLeading().startsWith("<"))
                throw failure("FERRY_UNAVAILABLE", "여객선 운항 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            var root = mapper.readTree(body).path("response");
            String code = root.path("header").path("resultCode").asText("");
            if (Set.of("20", "30", "31", "32").contains(code)) throw auth();
            if (Set.of("22", "23").contains(code)) throw quota();
            if (!"00".equals(code) && !"0000".equals(code)) throw invalid();
            return root.path("body");
        } catch (HttpTimeoutException e) {
            throw failure("FERRY_TIMEOUT", "여객선 운항 정보 조회 시간이 초과됐어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("FERRY_INTERRUPTED", "여객선 운항 정보 조회가 중단됐어요.");
        } catch (IOException e) {
            throw failure("FERRY_UNAVAILABLE", "여객선 운항 정보에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (RecommendationException e) {
            throw e;
        } catch (RuntimeException e) {
            // 예외 원인에도 키가 포함될 수 있으므로 공개 오류만 전달한다.
            throw invalid();
        }
    }

    // 결과가 한 건이면 item이 배열 대신 객체로, 없으면 빈 배열이나 빈 문자열로 온다.
    private List<JsonNode> items(JsonNode body) {
        var item = body.path("items").path("item");
        if (item.isArray()) { List<JsonNode> list = new ArrayList<>(); item.forEach(list::add); return list; }
        if (item.isObject()) return List.of(item);
        return List.of();
    }
    // 시각은 12자리 숫자(202610300950)로 온다. 읽지 못하면 그 편만 건너뛴다.
    private LocalDateTime time(JsonNode item, String field) {
        var value = item.path(field);
        String raw = value.isNumber() ? value.asText() : value.isTextual() ? value.asText().trim() : "";
        try {
            if (raw.matches("[0-9]{12}")) return LocalDateTime.parse(raw, TIME);
            if (raw.matches("[0-9]{14}")) return LocalDateTime.parse(raw.substring(0, 12), TIME);
        } catch (RuntimeException e) { return null; }
        return null;
    }
    private String text(JsonNode item, String field) {
        var value = item.path(field);
        if (value.isNumber()) return value.asText();
        if (!value.isTextual() || value.asText().length() > 100) return "";
        return value.asText().trim();
    }
    private RecommendationException invalid() { return failure("FERRY_RESPONSE", "여객선 운항 정보 응답을 확인하지 못했어요."); }
    private RecommendationException auth() {
        log.warn("여객선 운항 정보 인증에 실패했습니다. 인증키와 활용신청 승인 상태를 확인하세요.");
        return failure("FERRY_AUTH", UNAVAILABLE);
    }
    private RecommendationException quota() { return failure("FERRY_QUOTA", "여객선 운항 정보 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."); }
    private RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
