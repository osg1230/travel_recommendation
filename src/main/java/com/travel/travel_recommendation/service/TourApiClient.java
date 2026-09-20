package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TourApiProperties;
import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class TourApiClient {
    private static final Set<String> TYPES = Set.of("12", "14", "28", "32", "39");
    private final TourApiProperties properties;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public TourApiClient(TourApiProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create("https://apis.data.go.kr/B551011/KorService2/detailIntro2"), Duration.ofSeconds(8));
    }

    // 실제 서버 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    TourApiClient(TourApiProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) {
        this.properties = properties;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Optional<PlaceOperatingInfo> findOperatingInfo(String contentId, String contentTypeId) {
        if (contentId == null || !contentId.matches("[1-9][0-9]{0,19}") || !TYPES.contains(contentTypeId == null ? "" : contentTypeId))
            throw failure("TOUR_INPUT", "관광정보의 장소 ID와 유형을 확인해 주세요.");
        var data = get(endpoint, "&numOfRows=1&contentId=" + contentId + "&contentTypeId=" + contentTypeId);
        var items = data.path("items").path("item");
        if (data.path("totalCount").asInt() == 0) return Optional.empty();
        if (data.path("totalCount").asInt() != 1 || items.size() != 1) throw invalid();
        var item = items.get(0);
        if (!contentId.equals(item.path("contentid").asText()) || !contentTypeId.equals(item.path("contenttypeid").asText())) throw invalid();
        String hoursField = switch (contentTypeId) {
            case "12" -> "usetime";
            case "14" -> "usetimeculture";
            case "28" -> "usetimeleports";
            case "39" -> "opentimefood";
            default -> "";
        };
        String closedField = switch (contentTypeId) {
            case "12" -> "restdate";
            case "14" -> "restdateculture";
            case "28" -> "restdateleports";
            case "39" -> "restdatefood";
            default -> "";
        };
        return Optional.of(new PlaceOperatingInfo(contentId, contentTypeId, text(item, hoursField), text(item, closedField),
            "32".equals(contentTypeId) ? text(item, "checkintime") : "",
            "32".equals(contentTypeId) ? text(item, "checkouttime") : "", Instant.now()));
    }

    public record SearchPlace(String id, String type, String name, String address, double longitude, double latitude) {}

    public List<SearchPlace> search(String name) {
        if (name == null || name.isBlank() || name.length() > 200) throw failure("TOUR_INPUT", "장소 이름을 확인해 주세요.");
        var data = get(endpoint.resolve("searchKeyword2"), "&numOfRows=10&keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
        // 일부 결과만 보고 유일한 장소라고 판단하지 않는다.
        if (data.path("totalCount").asInt() > 10) return List.of();
        List<SearchPlace> result = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            String id = text(item, "contentid"), type = text(item, "contenttypeid");
            if (!id.matches("[1-9][0-9]{0,19}") || !TYPES.contains(type)) continue;
            // 좌표가 없거나 국내 범위를 벗어난 결과는 자동 연결하지 않는다.
            double x = coordinate(item, "mapx"), y = coordinate(item, "mapy");
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 124 || x > 132 || y < 33 || y > 39) continue;
            result.add(new SearchPlace(id, type, text(item, "title"), text(item, "addr1"), x, y));
        }
        return List.copyOf(result);
    }

    private JsonNode get(URI target, String parameters) {
        if (!properties.isConfigured())
            throw failure("TOUR_NOT_CONFIGURED", "TourAPI 인증키 설정이 필요해요.");
        // Decoding 키를 한 번만 인코딩한다. 요청 URL에는 키가 있어 로그에 남기지 않는다.
        String query = "?serviceKey=" + URLEncoder.encode(properties.serviceKey().trim(), StandardCharsets.UTF_8)
            + "&MobileOS=WEB&MobileApp=travel_recommendation&_type=json&pageNo=1" + parameters;
        try {
            var request = HttpRequest.newBuilder(URI.create(target + query)).timeout(timeout).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) throw auth();
            if (response.statusCode() == 429) throw quota();
            if (response.statusCode() != 200) throw failure("TOUR_UNAVAILABLE", "관광정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            String body = response.body();
            // 게이트웨이는 JSON 요청에도 XML 오류를 보낼 수 있다. 원문은 반환하지 않는다.
            if (body.stripLeading().startsWith("<")) {
                if (body.contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR") || body.contains("SERVICE_ACCESS_DENIED_ERROR")
                        || body.contains("DEADLINE_HAS_EXPIRED_ERROR") || body.contains("SERVICE_KEY_IS_NULL")) throw auth();
                if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) throw quota();
                throw invalid();
            }
            var root = mapper.readTree(body).path("response");
            String code = root.path("header").path("resultCode").asText("");
            if (Set.of("20", "30", "31", "32").contains(code)) throw auth();
            if (Set.of("22", "23").contains(code)) throw quota();
            if (!"0000".equals(code) && !"00".equals(code)) throw invalid();
            var data = root.path("body");
            if (!data.path("totalCount").asText("").matches("[0-9]+")) throw invalid();
            int count = data.path("totalCount").asInt(-1);
            var items = data.path("items").path("item");
            if (count == 0) {
                if ((items.isArray() && !items.isEmpty()) || items.isObject()) throw invalid();
                return data;
            }
            if (count < 1 || !items.isArray() || items.size() != Math.min(count, parameters.startsWith("&numOfRows=1&") ? 1 : 10)) throw invalid();
            return data;
        } catch (HttpTimeoutException e) {
            throw failure("TOUR_TIMEOUT", "관광정보 조회 시간이 초과됐어요. 다시 시도해 주세요.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw failure("TOUR_INTERRUPTED", "관광정보 조회가 중단됐어요.");
        } catch (IOException e) {
            throw failure("TOUR_UNAVAILABLE", "관광정보에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (RecommendationException e) {
            throw e;
        } catch (RuntimeException e) {
            // 예외 원인에도 키가 포함될 수 있으므로 공개 오류만 전달한다.
            throw invalid();
        }
    }

    // 좌표는 제공자가 문자열로도 숫자로도 보낼 수 있다. 읽지 못하면 그 결과만 건너뛴다.
    private double coordinate(JsonNode item, String field) {
        var value = item.path(field);
        if (value.isNumber()) return value.doubleValue();
        if (!value.isTextual()) return Double.NaN;
        try { return Double.parseDouble(value.asText().trim()); } catch (NumberFormatException ignored) { return Double.NaN; }
    }

    private String text(JsonNode item, String field) {
        if (field.isEmpty() || !item.hasNonNull(field)) return "";
        var value = item.path(field);
        if (!value.isTextual() || value.asText().length() > 4000) throw invalid();
        return value.asText().trim();
    }
    private RecommendationException invalid() { return failure("TOUR_RESPONSE", "관광정보 응답을 확인하지 못했어요."); }
    private RecommendationException auth() { return failure("TOUR_AUTH", "TourAPI 인증키와 활용신청 승인 상태를 확인해 주세요."); }
    private RecommendationException quota() { return failure("TOUR_QUOTA", "관광정보 조회 한도에 도달했어요. 잠시 후 다시 시도해 주세요."); }
    private RecommendationException failure(String code, String message) { return new RecommendationException(code, message); }
}
