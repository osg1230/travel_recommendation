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
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/**
 * 국토교통부 TAGO 시외버스정보(SFR-07-02·06-05). 출발·도착 터미널 사이의 직행 배차만 조회한다.
 * TAGO는 당일 배차 정보만 제공하므로 여행 당일의 시간표로 취급하지 않는다(SFR-06-06).
 */
@Component
public class IntercityBusClient {
    /** 직행 배차 한 건. 도착 시각은 운수사가 알린 예정 시각이다. */
    public record Trip(LocalDateTime departure, LocalDateTime arrival, String grade, Integer charge) {
        public int minutes() { return (int) Duration.between(departure, arrival).toMinutes(); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    // 실제 응답은 초까지 붙은 14자리 숫자(20260921071000)다. 12자리도 함께 받는다.
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    private static final DateTimeFormatter TIME_SECONDS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final TagoProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;
    // 터미널 ID는 자주 바뀌지 않으므로 이름으로 한 번 찾은 값을 실행 중에 재사용한다.
    private final Map<String, String> terminalIds = new ConcurrentHashMap<>();

    @Autowired
    public IntercityBusClient(TagoProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create("https://apis.data.go.kr/1613000/SuburbsBusInfo/"), Duration.ofSeconds(8));
    }
    // 실제 서버 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    IntercityBusClient(TagoProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.properties = properties; this.mapper = mapper; this.endpoint = endpoint; this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean configured() { return properties.isConfigured(); }

    /** 터미널 이름이 정확히 같은 결과 하나만 받는다. 비슷한 이름을 추측해 고르지 않는다. */
    public String terminalId(String terminalName) {
        if (terminalName == null || terminalName.isBlank() || terminalName.length() > 60) throw invalid();
        String cached = terminalIds.get(terminalName);
        if (cached != null) return cached;
        String found = null;
        for (var item : items(get("GetSuberbsBusTrminlList", "&numOfRows=20&terminalNm="
                + URLEncoder.encode(terminalName, StandardCharsets.UTF_8)))) {
            if (!terminalName.equals(text(item, "terminalNm"))) continue;
            String id = text(item, "terminalId");
            if (!id.matches("[A-Za-z0-9]{1,20}") || (found != null && !found.equals(id))) throw invalid();
            found = id;
        }
        if (found == null) throw failure("BUS_TERMINAL_NOT_FOUND", terminalName + "의 시외버스 터미널 정보를 찾지 못했어요.");
        terminalIds.put(terminalName, found);
        return found;
    }

    /** 오늘(date) 출발하는 직행 배차. 출발 시각 순서로 돌려준다. */
    public List<Trip> trips(String departureId, String arrivalId, LocalDate date) {
        if (departureId == null || arrivalId == null || date == null || departureId.equals(arrivalId)) throw invalid();
        List<Trip> result = new ArrayList<>();
        for (var item : items(get("GetStrtpntAlocFndSuberbsBusInfo", "&numOfRows=100&depTerminalId=" + departureId
                + "&arrTerminalId=" + arrivalId + "&depPlandTime=" + date.format(DATE)))) {
            var departure = time(item, "depPlandTime");
            var arrival = time(item, "arrPlandTime");
            // 날짜가 다르거나 도착이 출발보다 이르거나 하루를 넘는 배차는 잘못된 값으로 보고 쓰지 않는다.
            if (departure == null || arrival == null || !departure.toLocalDate().equals(date)
                    || !arrival.isAfter(departure) || arrival.isAfter(departure.plusHours(12))) continue;
            result.add(new Trip(departure, arrival, text(item, "gradeNm"), number(item, "charge")));
        }
        result.sort(Comparator.comparing(Trip::departure));
        return List.copyOf(result);
    }

    private JsonNode get(String operation, String parameters) {
        if (!properties.isConfigured()) throw failure("BUS_NOT_CONFIGURED", "시외버스 정보 인증키 설정이 필요해요.");
        // Decoding 키를 한 번만 인코딩한다. 요청 URL에는 키가 있어 로그에 남기지 않는다.
        String query = "?serviceKey=" + URLEncoder.encode(properties.serviceKey().trim(), StandardCharsets.UTF_8)
            + "&_type=json&pageNo=1" + parameters;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint.resolve(operation) + query)).timeout(timeout).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            String body = response.body() == null ? "" : response.body();
            // 게이트웨이 인증 오류는 403과 함께 오며 JSON·XML 어느 쪽일 수도 있다. 원문은 반환하지 않는다.
            if (body.contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR") || body.contains("SERVICE_ACCESS_DENIED_ERROR")
                    || body.contains("DEADLINE_HAS_EXPIRED_ERROR") || body.contains("SERVICE_KEY_IS_NULL")
                    || response.statusCode() == 401 || response.statusCode() == 403) throw auth();
            if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS") || response.statusCode() == 429) throw quota();
            if (response.statusCode() != 200 || body.stripLeading().startsWith("<"))
                throw failure("BUS_UNAVAILABLE", "시외버스 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            var root = mapper.readTree(body).path("response");
            String code = root.path("header").path("resultCode").asText("");
            if (Set.of("20", "30", "31", "32").contains(code)) throw auth();
            if (Set.of("22", "23").contains(code)) throw quota();
            if (!"00".equals(code) && !"0000".equals(code)) throw invalid();
            return root.path("body");
        } catch (HttpTimeoutException e) {
            throw failure("BUS_TIMEOUT", "시외버스 정보 조회 시간이 초과됐어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("BUS_INTERRUPTED", "시외버스 정보 조회가 중단됐어요.");
        } catch (IOException e) {
            throw failure("BUS_UNAVAILABLE", "시외버스 정보에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (RecommendationException e) {
            throw e;
        } catch (RuntimeException e) {
            // 예외 원인에도 키가 포함될 수 있으므로 공개 오류만 전달한다.
            throw invalid();
        }
    }

    // 결과가 한 건이면 item이 배열 대신 객체로, 없으면 빈 문자열로 올 수 있다.
    private List<JsonNode> items(JsonNode body) {
        var item = body.path("items").path("item");
        if (item.isArray()) { List<JsonNode> list = new ArrayList<>(); item.forEach(list::add); return list; }
        if (item.isObject()) return List.of(item);
        return List.of();
    }
    // 시각은 숫자(20260921143000)나 문자열로 온다. 읽지 못하면 그 배차만 건너뛴다.
    private LocalDateTime time(JsonNode item, String field) {
        var value = item.path(field);
        String raw = value.isNumber() ? value.asText() : value.isTextual() ? value.asText().trim() : "";
        try {
            if (raw.matches("[0-9]{14}")) return LocalDateTime.parse(raw, TIME_SECONDS);
            if (raw.matches("[0-9]{12}")) return LocalDateTime.parse(raw, TIME);
        } catch (RuntimeException e) { return null; }
        return null;
    }
    private Integer number(JsonNode item, String field) {
        var value = item.path(field);
        String raw = value.isNumber() ? value.asText() : value.isTextual() ? value.asText().trim() : "";
        if (!raw.matches("[0-9]{1,7}")) return null;
        return Integer.valueOf(raw);
    }
    private String text(JsonNode item, String field) {
        var value = item.path(field);
        if (value.isNumber()) return value.asText();
        if (!value.isTextual() || value.asText().length() > 200) return "";
        return value.asText().trim();
    }
    private RecommendationException invalid() { return failure("BUS_RESPONSE", "시외버스 정보 응답을 확인하지 못했어요."); }
    private RecommendationException auth() { return failure("BUS_AUTH", "시외버스 정보 인증키와 활용신청 승인 상태를 확인해 주세요."); }
    private RecommendationException quota() { return failure("BUS_QUOTA", "시외버스 정보 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."); }
    private RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
