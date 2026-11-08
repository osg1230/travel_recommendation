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
 * 국토교통부 TAGO 시외버스정보·고속버스정보(SFR-07-02·06-05). 출발·도착 터미널 사이의 직행 배차만 조회한다.
 * TAGO는 당일 배차 정보만 제공하므로 여행 당일의 시간표로 취급하지 않는다(SFR-06-06).
 */
@Component
public class IntercityBusClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(IntercityBusClient.class);
    /** 키가 없거나 인증에 실패했을 때 사용자에게 보여 주는 문장. 사용자가 고칠 수 없는 원인은 로그에만 남긴다. */
    private static final String UNAVAILABLE = "지금은 버스 정보를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.";
    /** TAGO 버스 서비스 종류. 두 서비스는 주소·오퍼레이션 이름만 다르고 요청·응답 형식은 같다. */
    public enum Service {
        INTERCITY("시외버스", "SuburbsBusInfo/", "GetSuberbsBusTrminlList", "GetStrtpntAlocFndSuberbsBusInfo"),
        EXPRESS("고속버스", "ExpBusInfo/", "GetExpBusTrminlList", "GetStrtpntAlocFndExpbusInfo");
        private final String label, path, terminalOperation, tripOperation;
        Service(String label, String path, String terminalOperation, String tripOperation) {
            this.label = label; this.path = path; this.terminalOperation = terminalOperation; this.tripOperation = tripOperation;
        }
        public String label() { return label; }
    }
    /** 직행 배차 한 건. 도착 시각은 운수사가 알린 예정 시각이다. */
    public record Trip(LocalDateTime departure, LocalDateTime arrival, String grade, Integer charge) {
        public int minutes() { return (int) Duration.between(departure, arrival).toMinutes(); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    static final int TRIP_PAGE_SIZE = 100, MAX_TRIP_PAGES = 5;
    /** TAGO ì¡°í ê²°ê³¼ë¥¼ ë¤ì ì°ë ìê°. ë²ì¤Â·í­ê³µÂ·ì´ì°¨Â·ì¬ê°ì  í´ë¼ì´ì¸í¸ê° ê°ì ê°ì ì´ë¤. */
    static final Duration TAGO_CACHE_TTL = Duration.ofMinutes(20);
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
        this(properties, mapper, URI.create("https://apis.data.go.kr/1613000/"), Duration.ofSeconds(8));
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
        for (var item : items(get(Service.INTERCITY, Service.INTERCITY.terminalOperation, "&pageNo=1&numOfRows=20&terminalNm="
                + URLEncoder.encode(terminalName, StandardCharsets.UTF_8)))) {
            if (!terminalName.equals(text(item, "terminalNm"))) continue;
            String id = text(item, "terminalId");
            if (!id.matches("[A-Za-z0-9]{1,20}")) throw invalid();
            // 같은 이름이 다른 ID로 여러 개면 어느 터미널인지 고를 수 없다. 응답 오류와 구분해 호출한 쪽이 판단하게 한다.
            if (found != null && !found.equals(id))
                throw failure("BUS_TERMINAL_AMBIGUOUS", terminalName + " 이름의 시외버스 터미널이 여러 곳이라 하나로 정하지 못했어요.");
            found = id;
        }
        if (found == null) throw failure("BUS_TERMINAL_NOT_FOUND", terminalName + "의 시외버스 터미널 정보를 찾지 못했어요.");
        terminalIds.put(terminalName, found);
        return found;
    }

    public record Terminal(String id, String name) {}

    /**
     * 이름 일부로 찾은 터미널 목록(예: "서울" → 동서울·서울남부). 사용자가 입력한 출발지를 터미널로 바꿀 때 쓴다.
     * 너무 많으면 사용자가 더 구체적으로 적도록 limit개까지만 돌려준다.
     */
    public List<Terminal> searchTerminals(String keyword, int limit) { return searchTerminals(Service.INTERCITY, keyword, limit); }

    /**
     * 이름이 정확히 같은 터미널의 ID 전부. 고속버스는 같은 이름이 승강장별로 여러 ID(예: 동서울 4개)로 등록되어
     * 있어 하나만 고르지 않고 모두 조회한다. 없으면 빈 목록이다.
     */
    public List<String> terminalIds(Service service, String terminalName) {
        if (terminalName == null || terminalName.isBlank() || terminalName.length() > 60) throw invalid();
        return searchTerminals(service, terminalName, 20).stream().filter(t -> t.name().equals(terminalName)).map(Terminal::id).toList();
    }

    /** 터미널 이름 검색도 배차와 같이 잠시 다시 쓴다. 버스 안내 한 번에 출발·도착 후보마다 같은 이름을 여러 번 찾기 때문이다. */
    private final ExpiringCache<String, List<Terminal>> terminalCache = new ExpiringCache<>(TAGO_CACHE_TTL, 500);

    public List<Terminal> searchTerminals(Service service, String keyword, int limit) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 30 || limit < 1) throw invalid();
        return terminalCache.get(service + ":" + keyword.strip() + ":" + limit, () -> loadTerminals(service, keyword, limit));
    }

    private List<Terminal> loadTerminals(Service service, String keyword, int limit) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 30 || limit < 1) throw invalid();
        List<Terminal> result = new ArrayList<>();
        for (var item : items(get(service, service.terminalOperation, "&pageNo=1&numOfRows=50&terminalNm="
                + URLEncoder.encode(keyword.strip(), StandardCharsets.UTF_8)))) {
            String id = text(item, "terminalId"), name = text(item, "terminalNm");
            if (!id.matches("[A-Za-z0-9]{1,20}") || name.isBlank() || !name.contains(keyword.strip())) continue;
            if (result.stream().noneMatch(t -> t.id().equals(id))) result.add(new Terminal(id, name));
            if (result.size() == limit) break;
        }
        return List.copyOf(result);
    }

    /** 오늘(date) 출발하는 직행 시외버스 배차. 출발 시각 순서로 돌려준다. */
    public List<Trip> trips(String departureId, String arrivalId, LocalDate date) { return trips(Service.INTERCITY, departureId, arrivalId, date); }

    /** 같은 구간·날짜를 TAGO_CACHE_TTL 동안 다시 부르지 않는다. TAGO는 당일 배차·운항 자료라 그 사이 결과가 같고, 버스 안내 한 번에 수십 번 부르던 호출을 줄인다. 실패는 보관하지 않는다. */
    private final ExpiringCache<String, List<Trip>> tripsCache = new ExpiringCache<>(TAGO_CACHE_TTL, 500);

    public List<Trip> trips(Service service, String departureId, String arrivalId, LocalDate date) {
        return tripsCache.get(service+":"+departureId+":"+arrivalId+":"+date, () -> loadTrips(service, departureId, arrivalId, date));
    }

    private List<Trip> loadTrips(Service service, String departureId, String arrivalId, LocalDate date) {
        if (departureId == null || arrivalId == null || date == null || departureId.equals(arrivalId)
                || !departureId.matches("[A-Za-z0-9]{1,20}") || !arrivalId.matches("[A-Za-z0-9]{1,20}")) throw invalid();
        List<Trip> result = new ArrayList<>();
        // 배차가 한 페이지(100건)를 넘으면 뒤 페이지까지 읽어야 첫차·막차·횟수가 맞다. 무한 반복을 막으려고 페이지 수를 제한한다.
        int read = 0;
        for (int page = 1; page <= MAX_TRIP_PAGES; page++) {
            var body = get(service, service.tripOperation, "&pageNo=" + page + "&numOfRows=" + TRIP_PAGE_SIZE
                + "&depTerminalId=" + departureId + "&arrTerminalId=" + arrivalId + "&depPlandTime=" + date.format(DATE));
            var items = items(body);
            for (var item : items) {
                var departure = time(item, "depPlandTime");
                var arrival = time(item, "arrPlandTime");
                // 날짜가 다르거나 도착이 출발보다 이르거나 하루를 넘는 배차는 잘못된 값으로 보고 쓰지 않는다.
                // 막차가 "24:00"으로 오면 다음 날 00:00으로 읽으므로, 그 경우만 조회일의 배차로 인정한다.
                boolean sameDay = departure != null && (departure.toLocalDate().equals(date) || departure.equals(date.plusDays(1).atStartOfDay()));
                if (departure == null || arrival == null || !sameDay
                        || !arrival.isAfter(departure) || arrival.isAfter(departure.plusHours(12))) continue;
                result.add(new Trip(departure, arrival, text(item, "gradeNm"), number(item, "charge")));
            }
            read += items.size();
            int total = body.path("totalCount").asInt(read);
            if (items.isEmpty() || read >= total) break;
            // 제한 페이지까지 읽고도 남아 있으면 일부만 보고 요약하지 않는다.
            if (page == MAX_TRIP_PAGES) throw failure("BUS_TOO_MANY", service.label() + " 배차가 너무 많아 요약하지 못했어요.");
        }
        result.sort(Comparator.comparing(Trip::departure));
        return List.copyOf(result);
    }

    private JsonNode get(Service service, String operation, String parameters) {
        if (!properties.isConfigured()) throw failure("BUS_NOT_CONFIGURED", UNAVAILABLE);
        // Decoding 키를 한 번만 인코딩한다. 요청 URL에는 키가 있어 로그에 남기지 않는다.
        String query = "?serviceKey=" + URLEncoder.encode(properties.serviceKey().trim(), StandardCharsets.UTF_8)
            + "&_type=json" + parameters;
        try {
            var request = HttpRequest.newBuilder(URI.create(endpoint.resolve(service.path + operation) + query)).timeout(timeout).GET().build();
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
        // 고속버스 막차는 "2400"(24:00)으로 오기도 한다. 그날의 끝, 곧 다음 날 00:00으로 읽는다.
        if (raw.matches("[0-9]{8}2400(00)?")) {
            try { return LocalDate.parse(raw.substring(0, 8), DATE).plusDays(1).atStartOfDay(); }
            catch (RuntimeException e) { return null; }
        }
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
    private RecommendationException auth() {
        log.warn("시외버스 정보 인증에 실패했습니다. 인증키와 활용신청 승인 상태를 확인하세요.");
        return failure("BUS_AUTH", UNAVAILABLE);
    }
    private RecommendationException quota() { return failure("BUS_QUOTA", "시외버스 정보 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."); }
    private RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
