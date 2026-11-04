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
 * 국토교통부 TAGO 열차정보. 출발역과 도착역 사이 하루 열차(종류·열차 번호·출발·도착 예정 시각·어른 요금)를 조회한다.
 * 시외버스·항공 정보와 같은 공공데이터포털 계정 키를 쓰며 활용신청은 따로 해야 한다. 2026-11-03 확인으로는 몇 주 뒤 날짜는 0건으로 온다.
 * 요청·응답 처리 방식은 FlightClient와 같다.
 */
@Component
public class TrainClient {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TrainClient.class);
    /** 키가 없거나 인증에 실패했을 때 사용자에게 보여 주는 문장. 사용자가 고칠 수 없는 원인은 로그에만 남긴다. */
    private static final String UNAVAILABLE = "지금은 열차 운행 정보를 불러올 수 없어요. 잠시 후 다시 시도해 주세요.";
    /** 열차 한 편. 시각은 코레일 등 운영사가 알린 예정 시각이다. */
    public record Train(LocalDateTime departure, LocalDateTime arrival, String grade, String trainNo, Integer adultCharge) {
        public int minutes() { return (int) Duration.between(departure, arrival).toMinutes(); }
    }
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmm");
    static final int PAGE_SIZE = 100, MAX_PAGES = 3;
    private final TagoProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public TrainClient(TagoProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create("https://apis.data.go.kr/1613000/TrainInfo/"), Duration.ofSeconds(8));
    }
    // 실제 서버 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    TrainClient(TagoProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.properties = properties; this.mapper = mapper; this.endpoint = endpoint; this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean configured() { return properties.isConfigured(); }

    /** date에 출발역에서 도착역으로 가는 열차. 출발 시각 순서이며 없으면 빈 목록이다. 역 코드는 TAGO 형식(예: NAT010000)이다. */
    public List<Train> trains(String departureStationId, String arrivalStationId, LocalDate date) {
        if (departureStationId == null || arrivalStationId == null || date == null || departureStationId.equals(arrivalStationId)
                || !departureStationId.matches("NAT[0-9A-Z]{6}") || !arrivalStationId.matches("NAT[0-9A-Z]{6}")) throw invalid();
        List<Train> result = new ArrayList<>();
        int read = 0;
        for (int page = 1; page <= MAX_PAGES; page++) {
            var body = get("GetStrtpntAlocFndTrainInfo", "&pageNo=" + page + "&numOfRows=" + PAGE_SIZE + "&depPlaceId=" + departureStationId
                + "&arrPlaceId=" + arrivalStationId + "&depPlandTime=" + date.format(DATE));
            var items = items(body);
            for (var item : items) {
                var departure = time(item, "depplandtime");
                var arrival = time(item, "arrplandtime");
                // 날짜가 다르거나 도착이 출발보다 이르거나 비정상적으로 긴 열차는 잘못된 값으로 보고 쓰지 않는다.
                if (departure == null || arrival == null || !departure.toLocalDate().equals(date)
                        || !arrival.isAfter(departure) || arrival.isAfter(departure.plusHours(10))) continue;
                result.add(new Train(departure, arrival, text(item, "traingradename"), text(item, "trainno"), number(item, "adultcharge")));
            }
            read += items.size();
            int total = body.path("totalCount").asInt(read);
            if (items.isEmpty() || read >= total) break;
            if (page == MAX_PAGES) throw failure("TRAIN_TOO_MANY", "열차가 너무 많아 요약하지 못했어요.");
        }
        result.sort(Comparator.comparing(Train::departure));
        return List.copyOf(result);
    }

    private JsonNode get(String operation, String parameters) {
        if (!properties.isConfigured()) throw failure("TRAIN_NOT_CONFIGURED", UNAVAILABLE);
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
                throw failure("TRAIN_UNAVAILABLE", "열차 운행 정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            var root = mapper.readTree(body).path("response");
            String code = root.path("header").path("resultCode").asText("");
            if (Set.of("20", "30", "31", "32").contains(code)) throw auth();
            if (Set.of("22", "23").contains(code)) throw quota();
            if (!"00".equals(code) && !"0000".equals(code)) throw invalid();
            return root.path("body");
        } catch (HttpTimeoutException e) {
            throw failure("TRAIN_TIMEOUT", "열차 운행 정보 조회 시간이 초과됐어요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("TRAIN_INTERRUPTED", "열차 운행 정보 조회가 중단됐어요.");
        } catch (IOException e) {
            throw failure("TRAIN_UNAVAILABLE", "열차 운행 정보에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
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
    // 시각은 14자리 문자열(20261105050600)로 온다. 12자리도 받아 두고, 읽지 못하면 그 열차만 건너뛴다.
    private LocalDateTime time(JsonNode item, String field) {
        var value = item.path(field);
        String raw = value.isNumber() ? value.asText() : value.isTextual() ? value.asText().trim() : "";
        try {
            if (raw.matches("[0-9]{12}")) return LocalDateTime.parse(raw, TIME);
            if (raw.matches("[0-9]{14}")) return LocalDateTime.parse(raw.substring(0, 12), TIME);
        } catch (RuntimeException e) { return null; }
        return null;
    }
    // 요금은 숫자 문자열로 오며 0이면 요금 정보가 없는 것으로 본다.
    private Integer number(JsonNode item, String field) {
        var value = item.path(field);
        String raw = value.isNumber() ? value.asText() : value.isTextual() ? value.asText().trim() : "";
        if (!raw.matches("[0-9]{1,7}") || raw.matches("0+")) return null;
        return Integer.valueOf(raw);
    }
    private String text(JsonNode item, String field) {
        var value = item.path(field);
        if (value.isNumber()) return value.asText();
        if (!value.isTextual() || value.asText().length() > 200) return "";
        return value.asText().trim();
    }
    private RecommendationException invalid() { return failure("TRAIN_RESPONSE", "열차 운행 정보 응답을 확인하지 못했어요."); }
    private RecommendationException auth() {
        log.warn("열차 운행 정보 인증에 실패했습니다. 인증키와 활용신청 승인 상태를 확인하세요.");
        return failure("TRAIN_AUTH", UNAVAILABLE);
    }
    private RecommendationException quota() { return failure("TRAIN_QUOTA", "열차 운행 정보 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."); }
    private RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
