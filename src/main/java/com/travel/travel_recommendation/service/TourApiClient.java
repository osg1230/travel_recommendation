package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.TourApiProperties;
import com.travel.travel_recommendation.dto.PlaceOperatingInfo;
import com.travel.travel_recommendation.dto.PlacePetInfo;
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
    /** 법정동 코드는 거의 바뀌지 않고 여행지마다 같은 목록을 다시 받으므로 하루 동안 재사용한다. */
    private final ExpiringCache<String, JsonNode> districtLists = new ExpiringCache<>(Duration.ofHours(24), 100);
    /** 목적 시설·사찰·성당 검색 결과. '장소 더 찾아보기'가 같은 검색어를 되풀이해 하루 한도를 쓰지 않게 한다. */
    private final ExpiringCache<String, List<SearchPlace>> facilitySearches = new ExpiringCache<>(Duration.ofHours(24), 1000);
    /** 한도 초과나 인증 실패 뒤에는 어차피 실패할 호출을 보내지 않고 이 시각까지 바로 실패시킨다. */
    private volatile Instant blockedUntil;
    private volatile boolean blockedByQuota;

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
        // 주차 안내 원문. 문화시설·레포츠는 주차 가능 여부와 요금이 따로 와서 둘을 합쳐 적는다.
        String parkingField = switch (contentTypeId) {
            case "12" -> "parking";
            case "14" -> "parkingculture";
            case "28" -> "parkingleports";
            case "32" -> "parkinglodging";
            case "39" -> "parkingfood";
            default -> "";
        };
        String feeField = switch (contentTypeId) {
            case "14" -> "parkingfee";
            case "28" -> "parkingfeeleports";
            default -> "";
        };
        String parking = text(item, parkingField), fee = text(item, feeField);
        if (!fee.isBlank()) parking = (parking.isBlank() ? "" : parking + " · ") + "요금 " + fee;
        return Optional.of(new PlaceOperatingInfo(contentId, contentTypeId, text(item, hoursField), text(item, closedField),
            "32".equals(contentTypeId) ? text(item, "checkintime") : "",
            "32".equals(contentTypeId) ? text(item, "checkouttime") : "", parking, Instant.now()));
    }

    /**
     * 장소의 반려동물 동반 정보(detailPetTour2). 관광공사에 등록된 장소라도 반려동물 정보가 없으면 비어 있다.
     * 동반 구분(acmpyTypeCd)·가능한 동물(acmpyPsblCpam)·필요 사항(acmpyNeedMtr)·기타(etcAcmpyInfo) 원문을 그대로 담는다.
     */
    public Optional<PlacePetInfo> findPetInfo(String contentId) {
        if (contentId == null || !contentId.matches("[1-9][0-9]{0,19}")) throw failure("TOUR_INPUT", "관광정보의 장소 ID를 확인해 주세요.");
        var data = get(endpoint.resolve("detailPetTour2"), 1, "&contentId=" + contentId);
        var items = data.path("items").path("item");
        if (data.path("totalCount").asInt() == 0) return Optional.empty();
        if (data.path("totalCount").asInt() != 1 || items.size() != 1) throw invalid();
        var item = items.get(0);
        if (!contentId.equals(item.path("contentid").asText())) throw invalid();
        return Optional.of(new PlacePetInfo(contentId, text(item, "acmpyTypeCd"), text(item, "acmpyPsblCpam"),
            text(item, "acmpyNeedMtr"), text(item, "etcAcmpyInfo"), Instant.now()));
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
        return attractionItems(get(endpoint.resolve("searchKeyword2"), 30, "&keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8)), true);
    }

    /** 장소 소개문을 찾으려고 이름으로 관광지(12·14·28)를 검색한다. 사진 검색과 같지만 사진이 없는 곳도 받는다. */
    public List<SearchPlace> searchAttractions(String name) {
        if (name == null || name.isBlank() || name.length() > 200) throw failure("TOUR_INPUT", "장소 이름을 확인해 주세요.");
        return attractionItems(get(endpoint.resolve("searchKeyword2"), 30, "&keyword=" + URLEncoder.encode(name, StandardCharsets.UTF_8)), false);
    }

    /** 소개문에서 남기는 최대 글자 수. 앞부분에 장소의 특징이 모여 있고, AI 요청 길이를 줄이려고 자른다. */
    static final int OVERVIEW_CHARS = 600;

    /**
     * 장소의 관광공사 소개문(detailCommon2의 overview). 줄바꿈 태그 같은 HTML은 빼고 공백을 정리해 OVERVIEW_CHARS자까지 돌려준다.
     * 관광공사에 소개문이 없으면 비어 있다.
     */
    public Optional<String> findOverview(String contentId) {
        if (contentId == null || !contentId.matches("[1-9][0-9]{0,19}")) throw failure("TOUR_INPUT", "관광정보의 장소 ID를 확인해 주세요.");
        var data = get(endpoint.resolve("detailCommon2"), 1, "&contentId=" + contentId);
        var items = data.path("items").path("item");
        if (data.path("totalCount").asInt() == 0) return Optional.empty();
        if (data.path("totalCount").asInt() != 1 || items.size() != 1) throw invalid();
        var item = items.get(0);
        if (!contentId.equals(item.path("contentid").asText())) throw invalid();
        var overview = item.path("overview");
        if (!overview.isTextual()) return Optional.empty();
        String plain = overview.asText().replaceAll("<[^>]*>", " ").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&quot;", "\"").replace("&amp;", "&").replaceAll("\\s+", " ").strip();
        if (plain.length() > OVERVIEW_CHARS) plain = plain.substring(0, OVERVIEW_CHARS) + "…";
        return plain.isEmpty() ? Optional.empty() : Optional.of(plain);
    }

    /**
     * 법정동 코드 조회. provinceCode가 없으면 시·도 목록에서, 있으면 그 시·도의 시·군·구 목록에서 이름이 같은 코드를 찾는다.
     * 예) districtCode(null, "강원특별자치도") → "51", districtCode("51", "강릉시") → "150". 없으면 비어 있다.
     */
    public Optional<String> districtCode(String provinceCode, String name) {
        if (name == null || name.isBlank() || name.length() > 40) return Optional.empty();
        if (provinceCode != null && !provinceCode.matches("[0-9]{2,5}")) return Optional.empty();
        var data = districtList(provinceCode);
        for (var item : data.path("items").path("item")) {
            String code = item.path("code").asText("");
            if (name.equals(item.path("name").asText("")) && code.matches("[0-9]{2,5}")) return Optional.of(code);
        }
        return Optional.empty();
    }
    // provinceCode가 없으면 시·도 목록, 있으면 그 시·도의 시·군·구 목록이다.
    private JsonNode districtList(String provinceCode) {
        String extra = provinceCode == null ? "" : "&lDongRegnCd=" + provinceCode;
        return districtLists.get(extra, () -> get(endpoint.resolve("ldongCode2"), 100, extra));
    }

    /** 시·도 안의 시·군·구 코드 전체(예: "26" → 부산광역시 16개 구·군의 "110"·"140"…). 연관 관광지를 시·도 전체로 모을 때 쓴다. */
    public List<String> districtCodes(String provinceCode) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2}")) return List.of();
        var data = districtList(provinceCode);
        List<String> codes = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            String code = item.path("code").asText("");
            if (code.matches("[0-9]{3}")) codes.add(code);
        }
        return List.copyOf(codes);
    }

    /**
     * 시·도 안에서 시·군 이름(예: "수원시")의 시·군·구 코드. 수원·전주·포항처럼 일반구가 있는 시는 관광지가 구 코드로 등록돼
     * 시 코드(예: 110)로는 관광공사 분류 조회·연관 관광지가 0건이므로, 그 아래 구 코드들(예: 111·113·115·117)을 돌려준다.
     * 구가 없으면 시·군 코드 하나, 이름을 찾지 못하면 빈 목록이다.
     */
    public List<String> cityDistrictCodes(String provinceCode, String cityName) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2}") || cityName == null || cityName.isBlank() || cityName.length() > 40)
            return List.of();
        var data = districtList(provinceCode);
        String own = null; List<String> wards = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            String code = item.path("code").asText(""), name = item.path("name").asText("");
            if (!code.matches("[0-9]{3}")) continue;
            if (name.equals(cityName)) own = code;
            else if (name.startsWith(cityName + " ")) wards.add(code);
        }
        if (!wards.isEmpty()) return List.copyOf(wards);
        return own == null ? List.of() : List.of(own);
    }

    /** 시·도 코드와 그 안의 시·군·구 코드들. districts가 비어 있으면 시·도 전체(광역시·제주·세종)다. */
    public record RegionCodes(String province, List<String> districts) {}

    /**
     * 여행지 지역 이름을 법정동 코드로 바꾼다. "강원특별자치도 속초시" → 51·[210], "경기도 수원시" → 41·[111, 113, 115, 117](일반구),
     * "부산광역시" → 26·[], "세종특별자치시" → 36110·[]. 찾지 못하면 비어 있다.
     */
    public Optional<RegionCodes> regionCodes(String region) {
        if (region == null || region.isBlank()) return Optional.empty();
        String[] parts = region.strip().split("\\s+");
        var province = districtCode(null, parts[0]).orElse(null);
        if (province == null) return Optional.empty();
        if (parts.length < 2) return Optional.of(new RegionCodes(province, List.of()));
        var districts = cityDistrictCodes(province, parts[1]);
        return districts.isEmpty() ? Optional.empty() : Optional.of(new RegionCodes(province, districts));
    }

    /** 관광공사에 등록된 장소 하나의 이름과 새 분류체계 소분류 코드(예: "NA040100" 국립공원). */
    public record ClassifiedPlace(String name, String category) {}
    static final int CLASS_ROWS = 1000;

    /**
     * 지역(시·도 또는 시·군·구)에 등록된 장소 중 새 분류체계 대분류(topClass, 예: "NA" 자연관광·"HS" 역사관광)에 속한 곳의
     * 이름과 소분류를 받는다. 여행지마다 "할 수 있는 것"(소분류별 장소 수)을 세는 데 쓴다. 한 쪽 CLASS_ROWS행씩 maxPages쪽까지 읽는다.
     */
    public List<ClassifiedPlace> placesInClass(String provinceCode, String districtCode, String topClass, int maxPages) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2,5}") || topClass == null || !topClass.matches("[A-Z]{2}") || maxPages < 1)
            throw failure("TOUR_INPUT", "지역 코드와 분류를 확인해 주세요.");
        String extra = "&lclsSystm1=" + topClass + "&lDongRegnCd=" + provinceCode
            + (districtCode != null && districtCode.matches("[0-9]{3}") ? "&lDongSignguCd=" + districtCode : "");
        List<ClassifiedPlace> result = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            var data = get(endpoint.resolve("areaBasedList2"), page, CLASS_ROWS, extra);
            for (var item : data.path("items").path("item")) {
                try {
                    String name = text(item, "title"), category = text(item, "lclsSystm3");
                    if (!name.isBlank() && name.length() <= 100 && category.matches("[A-Z]{2}[0-9]{6}")) result.add(new ClassifiedPlace(name, category));
                } catch (RecommendationException ignored) {
                    // 한 항목의 형식 오류로 다른 장소까지 잃지 않는다.
                }
            }
            if ((long) page * CLASS_ROWS >= data.path("totalCount").asLong(0)) break;
        }
        return List.copyOf(result);
    }

    /**
     * 관광공사 '관광지별 연관 관광지 정보'(티맵 내비게이션 이동 데이터)의 한 행. center는 시·군·구의 중심 관광지,
     * name은 중심 관광지에 간 차량이 함께 많이 간 곳, rank는 그 순위(1이 가장 많음)다. large·middle·small은 대·중·소 분류다.
     */
    public record RelatedPlace(String center, String name, String districtCode, String large, String middle, String small, int rank) {}
    static final int RELATED_ROWS = 1000;

    /** 시·군·구(districtCode, 5자리 법정동 코드)의 연관 관광지를 month 기준으로 최대 maxPages쪽(쪽당 RELATED_ROWS행)까지 받는다. */
    public List<RelatedPlace> relatedPlaces(String districtCode, YearMonth month, int maxPages) {
        if (districtCode == null || !districtCode.matches("[0-9]{5}") || month == null || maxPages < 1)
            throw failure("TOUR_INPUT", "지역 코드를 확인해 주세요.");
        String extra = "&baseYm=" + month.format(DateTimeFormatter.ofPattern("yyyyMM"))
            + "&areaCd=" + districtCode.substring(0, 2) + "&signguCd=" + districtCode;
        List<RelatedPlace> result = new ArrayList<>();
        for (int page = 1; page <= maxPages; page++) {
            var data = get(endpoint.resolve("../TarRlteTarService1/areaBasedList1"), page, RELATED_ROWS, extra);
            for (var item : data.path("items").path("item")) {
                try {
                    String center = text(item, "tAtsNm"), name = text(item, "rlteTatsNm");
                    int rank = Integer.parseInt(text(item, "rlteRank"));
                    if (center.isBlank() || name.isBlank() || name.length() > 100 || rank < 1 || rank > 100) continue;
                    result.add(new RelatedPlace(center, name, text(item, "rlteSignguCd"), text(item, "rlteCtgryLclsNm"),
                        text(item, "rlteCtgryMclsNm"), text(item, "rlteCtgrySclsNm"), rank));
                } catch (RecommendationException | NumberFormatException ignored) {
                    // 한 행의 형식 오류로 다른 행까지 잃지 않는다.
                }
            }
            if ((long) page * RELATED_ROWS >= data.path("totalCount").asLong(0)) break;
        }
        return List.copyOf(result);
    }

    /**
     * 시·도(와 시·군·구) 안의 자연 관광지(대분류 A01, 관광지 12) 가운데 사진이 있는 곳을 제목순으로 받는다.
     * 여행지 대표 경치 사진을 고를 때 쓴다. districtCode가 없으면 시·도 전체에서 찾는다.
     */
    public List<SearchPlace> naturePhotos(String provinceCode, String districtCode) {
        if (provinceCode == null || !provinceCode.matches("[0-9]{2,5}")) throw failure("TOUR_INPUT", "지역 코드를 확인해 주세요.");
        String extra = "&lDongRegnCd=" + provinceCode + "&cat1=A01&contentTypeId=12&arrange=O";
        if (districtCode != null && districtCode.matches("[0-9]{2,5}")) extra += "&lDongSignguCd=" + districtCode;
        return attractionItems(get(endpoint.resolve("areaBasedList2"), 30, extra), true);
    }

    /** 관광지 검색·자연 관광지 목록의 항목을 SearchPlace로 바꾼다. 형식이 맞지 않는 항목과, needImage면 사진이 없는 항목은 뺀다. */
    private static List<SearchPlace> attractionItems(JsonNode data, boolean needImage) {
        List<SearchPlace> result = new ArrayList<>();
        for (var item : data.path("items").path("item")) {
            String id = item.path("contentid").asText(""), type = item.path("contenttypeid").asText("");
            String title = item.path("title").asText(""), image = item.path("firstimage").asText("");
            if (!id.matches("[1-9][0-9]{0,19}") || !Set.of("12", "14", "28").contains(type) || title.isBlank() || title.length() > 100
                    || (needImage && (image.isBlank() || image.length() > 500))) continue;
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
        return facilitySearches.get(keyword.strip(), () -> loadFacilities(keyword.strip()));
    }
    private List<SearchPlace> loadFacilities(String keyword) {
        var data = get(endpoint.resolve("searchKeyword2"), FACILITY_ROWS, "&keyword=" + URLEncoder.encode(keyword, StandardCharsets.UTF_8));
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
        return get(target, 1, rows, extra);
    }

    /** page쪽(1부터)을 rows행씩 받는다. 마지막 쪽은 남은 행 수만큼 와야 한다. */
    private JsonNode get(URI target, int page, int rows, String extra) {
        String parameters = "&numOfRows=" + rows + extra;
        if (!properties.isConfigured())
            throw failure("TOUR_NOT_CONFIGURED", "TourAPI 인증키 설정이 필요해요.");
        var until = blockedUntil;
        if (until != null && Instant.now().isBefore(until)) throw blockedByQuota ? quota() : auth();
        // Decoding 키를 한 번만 인코딩한다. 요청 URL에는 키가 있어 로그에 남기지 않는다.
        String query = "?serviceKey=" + URLEncoder.encode(properties.serviceKey().trim(), StandardCharsets.UTF_8)
            + "&MobileOS=WEB&MobileApp=travel_recommendation&_type=json&pageNo=" + page + parameters;
        try {
            var request = HttpRequest.newBuilder(URI.create(target + query)).timeout(timeout).GET().build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 401 || response.statusCode() == 403) throw blockForAuth();
            // 429는 잠깐 몰린 경우일 수 있어 하루 한도처럼 자정까지 막지 않고 인증 실패와 같은 시간만 쉰다.
            if (response.statusCode() == 429) { block(true, Instant.now().plus(BLOCK_AFTER_FAILURE)); throw quota(); }
            if (response.statusCode() != 200) throw failure("TOUR_UNAVAILABLE", "관광정보를 불러오지 못했어요. 잠시 후 다시 시도해 주세요.");
            String body = response.body();
            // 게이트웨이는 JSON 요청에도 XML 오류를 보낼 수 있다. 원문은 반환하지 않는다.
            if (body.stripLeading().startsWith("<")) {
                if (body.contains("SERVICE_KEY_IS_NOT_REGISTERED_ERROR") || body.contains("SERVICE_ACCESS_DENIED_ERROR")
                        || body.contains("DEADLINE_HAS_EXPIRED_ERROR") || body.contains("SERVICE_KEY_IS_NULL")) throw blockForAuth();
                if (body.contains("LIMITED_NUMBER_OF_SERVICE_REQUESTS")) throw blockForDailyQuota();
                throw invalid();
            }
            var root = mapper.readTree(body).path("response");
            String code = root.path("header").path("resultCode").asText("");
            if (Set.of("20", "30", "31", "32").contains(code)) throw blockForAuth();
            if (Set.of("22", "23").contains(code)) throw blockForDailyQuota();
            if (!"0000".equals(code) && !"00".equals(code)) throw invalid();
            var data = root.path("body");
            if (!data.path("totalCount").asText("").matches("[0-9]+")) throw invalid();
            int count = data.path("totalCount").asInt(-1);
            var items = data.path("items").path("item");
            if (count == 0) {
                if ((items.isArray() && !items.isEmpty()) || items.isObject()) throw invalid();
                return data;
            }
            if (count < 1 || !items.isArray() || items.size() != Math.min(count - (page - 1) * rows, rows)) throw invalid();
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

    /** 인증 실패·순간 과다 요청 뒤 쉬는 시간. 키를 고치거나 몰린 요청이 풀리면 곧 다시 쓸 수 있게 짧게 둔다. */
    private static final Duration BLOCK_AFTER_FAILURE = Duration.ofMinutes(10);
    private void block(boolean quota, Instant until) { blockedByQuota = quota; blockedUntil = until; }
    private RecommendationException blockForAuth() { block(false, Instant.now().plus(BLOCK_AFTER_FAILURE)); return auth(); }
    // 공공데이터포털의 하루 한도는 자정에 다시 채워진다.
    private RecommendationException blockForDailyQuota() {
        var korea = ZoneId.of("Asia/Seoul");
        block(true, LocalDate.now(korea).plusDays(1).atStartOfDay(korea).toInstant());
        return quota();
    }
}
