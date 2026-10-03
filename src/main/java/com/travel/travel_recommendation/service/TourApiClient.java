package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TourApiProperties;
import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import com.travel.travel_recommendation.dto.RegionalFestival;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.List;
import java.util.ArrayList;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

@Component
public class TourApiClient {
    private static final Set<String> TYPES = Set.of("12", "14", "28", "32", "39"), FACILITY_TYPES = Set.of("12", "14");
    static final int FACILITY_ROWS = 100, FESTIVAL_ROWS = 100;
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
        var data = get(endpoint, 1, "&contentId=" + contentId + "&contentTypeId=" + contentTypeId);
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

    /** image·copyright는 사진 검색(searchPhotos) 결과의 대표 사진 주소와 저작권 유형(Type1 등)이다. 그 밖의 검색에서는 빈 문자열이다. */
    public record SearchPlace(String id, String type, String name, String address, double longitude, double latitude,
                              String image, String copyright) {
        public SearchPlace(String id, String type, String name, String address, double longitude, double latitude) {
            this(id, type, name, address, longitude, latitude, "", "");
        }
    }

    public List<SearchPlace> search(String name) {
        if (name == null || name.isBlank() || name.length() > 200) throw failure("TOUR_INPUT", "장소 이름을 확인해 주세요.");
        var data = get(endpoint.resolve("searchKeyword2"), 10, "&keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8));
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

    /**
     * 장소 사진을 찾으려고 이름으로 관광지(12·14·28)를 검색한다. 운영정보 검색과 달리 결과가 많아도 앞 30건을 돌려주고,
     * 같은 곳인지는 호출하는 쪽이 이름과 위치로 가린다.
     */
    public List<SearchPlace> searchPhotos(String name) {
        if (name == null || name.isBlank() || name.length() > 200) throw failure("TOUR_INPUT", "장소 이름을 확인해 주세요.");
        return photoItems(get(endpoint.resolve("searchKeyword2"), 30, "&keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8)));
    }

    /**
     * 법정동 코드 조회. provinceCode가 없으면 시·도 목록에서, 있으면 그 시·도의 시·군·구 목록에서 이름이 같은 코드를 찾는다.
     * 예) districtCode(null, "강원특별자치도") → "51", districtCode("51", "강릉시") → "150". 없으면 비어 있다.
     */
    public Optional<String> districtCode(String provinceCode, String name) {
        if (name == null || name.isBlank() || name.length() > 40) return Optional.empty();
        if (provinceCode != null && !provinceCode.matches("[0-9]{2,5}")) return Optional.empty();
        var data = get(endpoint.resolve("ldongCode2"), 100, provinceCode == null ? "" : "&lDongRegnCd=" + provinceCode);
        for (var item : data.path("items").path("item")) {
            String code = item.path("code").asText("");
            if (name.equals(item.path("name").asText("")) && code.matches("[0-9]{2,5}")) return Optional.of(code);
        }
        return Optional.empty();
    }

    /**
     * 시·도(와 시·군·구) 안의 자연 관광지(대분류 A01, 관광지 12) 가운데 사진이 있는 곳을 제목순으로 받는다.
     * 여행지 대표 경치 사진을 고를 때 쓴다. districtCode가 없으면 시·도 전체에서 찾는다.
     */
    public List<SearchPlace> naturePhotos(String provinceCode, String districtCode) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2,5}")) throw failure("TOUR_INPUT", "지역 코드를 확인해 주세요.");
        String extra = "&lDongRegnCd=" + provinceCode + "&cat1=A01&contentTypeId=12&arrange=O";
        if (districtCode != null && districtCode.matches("[0-9]{2,5}")) extra += "&lDongSignguCd=" + districtCode;
        return photoItems(get(endpoint.resolve("areaBasedList2"), 30, extra));
    }

    /** 사진 검색·자연 관광지 목록의 항목을 SearchPlace로 바꾼다. 사진이 없거나 형식이 맞지 않는 항목은 뺀다. */
    private static List<SearchPlace> photoItems(JsonNode data) {
        List<SearchPlace> result = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            String id = item.path("contentid").asText(""), type = item.path("contenttypeid").asText("");
            String title = item.path("title").asText(""), image = item.path("firstimage").asText("");
            if (!id.matches("[1-9][0-9]{0,19}") || !Set.of("12", "14", "28").contains(type) || title.isBlank() || title.length() > 100
                    || image.isBlank() || image.length() > 500) continue;
            double x = item.path("mapx").asDouble(Double.NaN), y = item.path("mapy").asDouble(Double.NaN);
            result.add(new SearchPlace(id, type, title, item.path("addr1").asText(""), x, y, image, item.path("cpyrhtDivCd").asText("")));
        }
        return List.copyOf(result);
    }

    public boolean configured() { return properties.isConfigured(); }

    /**
     * 여행 목적 시설을 제목 키워드로 전국에서 찾는다(예: "갯벌체험장"). 관광지(12)·문화시설(14)만 받아 음식점·숙박·축제·코스는 뺀다.
     * TourAPI는 제목에 검색어가 그대로 들어 있어야 찾으므로 띄어쓰기는 호출하는 쪽에서 맞춘다. 앞쪽 최대 100건만 본다.
     */
    public List<SearchPlace> searchFacilities(String keyword) {
        if (keyword == null || keyword.isBlank() || keyword.length() > 40) throw failure("TOUR_INPUT", "검색어를 확인해 주세요.");
        var data = get(endpoint.resolve("searchKeyword2"), FACILITY_ROWS, "&keyword=" + URLEncoder.encode(keyword.strip(), StandardCharsets.UTF_8));
        List<SearchPlace> result = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            try {
                String id = text(item, "contentid"), type = text(item, "contenttypeid");
                if (!id.matches("[1-9][0-9]{0,19}") || !FACILITY_TYPES.contains(type)) continue;
                double x = coordinate(item, "mapx"), y = coordinate(item, "mapy");
                if (!Double.isFinite(x) || !Double.isFinite(y) || x < 124 || x > 132 || y < 33 || y > 39) continue;
                String title = text(item, "title"), address = text(item, "addr1");
                if (title.isBlank() || title.length() > 100 || address.isBlank()) continue;
                result.add(new SearchPlace(id, type, title, address, x, y));
            } catch (RecommendationException ignored) {
                // 한 항목의 형식 오류로 다른 시설까지 잃지 않는다.
            }
        }
        return List.copyOf(result);
    }

    /**
     * 시·도(법정동 시·도 코드, 예: 충북 "43")에서 from 날짜에 진행 중이거나 그 뒤에 열리는 축제를 찾는다.
     * 관광공사는 종료일이 from 이후인 행사를 돌려준다. 앞쪽 최대 100건만 보고, 날짜·제목이 없는 항목은 건너뛴다.
     */
    public List<RegionalFestival> searchFestivals(String regionCode, LocalDate from) {
        if (regionCode == null || !regionCode.matches("[0-9]{2,5}") || from == null) throw failure("TOUR_INPUT", "축제를 찾을 지역을 확인해 주세요.");
        var data = get(endpoint.resolve("searchFestival2"), FESTIVAL_ROWS,
            "&lDongRegnCd=" + regionCode + "&eventStartDate=" + from.format(DateTimeFormatter.BASIC_ISO_DATE));
        List<RegionalFestival> result = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            try {
                String id = text(item, "contentid"), title = text(item, "title");
                if (!id.matches("[1-9][0-9]{0,19}") || title.isBlank() || title.length() > 100) continue;
                // 다른 시·도 행사가 섞여 오면 보여 주지 않는다.
                if (!regionCode.equals(text(item, "lDongRegnCd"))) continue;
                var start = LocalDate.parse(text(item, "eventstartdate"), DateTimeFormatter.BASIC_ISO_DATE);
                var end = LocalDate.parse(text(item, "eventenddate"), DateTimeFormatter.BASIC_ISO_DATE);
                if (end.isBefore(start)) continue;
                // 사진은 출처 표시만 하면 쓸 수 있는 유형(Type1·Type3)의 https 주소만 쓴다.
                String image = text(item, "firstimage");
                if (!image.startsWith("https://") || image.length() > 500 || !Set.of("Type1", "Type3").contains(text(item, "cpyrhtDivCd"))) image = "";
                // 행사 장소 좌표는 일정에 넣을 실제 장소를 찾을 때만 쓴다. 국내 범위를 벗어나면 없는 것으로 둔다.
                double x = coordinate(item, "mapx"), y = coordinate(item, "mapy");
                boolean located = x >= 124 && x <= 132 && y >= 33 && y <= 39;
                result.add(new RegionalFestival(id, title, start, end, text(item, "addr1"), text(item, "addr2"), image, text(item, "tel"),
                    located ? x : null, located ? y : null));
            } catch (RecommendationException | DateTimeParseException ignored) {
                // 한 행사의 형식 오류로 다른 축제까지 잃지 않는다.
            }
        }
        return List.copyOf(result);
    }

    private JsonNode get(URI target, int rows, String extra) {
        String parameters = "&numOfRows=" + rows + extra;
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
            if (count < 1 || !items.isArray() || items.size() != Math.min(count, rows)) throw invalid();
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
