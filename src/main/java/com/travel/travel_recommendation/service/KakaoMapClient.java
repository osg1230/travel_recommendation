package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.*;

/** 좌표와 이동시간은 AI가 만든 값 대신 지도 API 응답에서 가져온다. */
@Component
public class KakaoMapClient {
    public enum Transport { CAR, PUBLIC_TRANSIT }
    public record Location(String address, double longitude, double latitude, Instant checkedAt) {
        public Location {
            if (address == null || address.isBlank() || checkedAt == null
                    || !Double.isFinite(longitude) || !Double.isFinite(latitude)
                    || longitude < 124 || longitude > 132 || latitude < 33 || latitude > 39)
                throw new IllegalArgumentException("국내 장소 좌표를 확인해 주세요.");
        }
    }
    public record TravelTime(Transport transport, int seconds, int meters, Instant checkedAt) {
        // 초 단위 응답을 올림하여 이동시간을 짧게 잡지 않는다.
        public int minutes() { return (seconds + 59) / 60; }
    }
    private final KakaoMapProperties properties;
    private final ObjectMapper mapper;
    private final URI localEndpoint, carEndpoint;
    private final Duration timeout;
    private final HttpClient http;

    @Autowired
    public KakaoMapClient(KakaoMapProperties properties, ObjectMapper mapper) {
        this(properties, mapper, URI.create("https://dapi.kakao.com"),
            URI.create("https://apis-navi.kakaomobility.com"), Duration.ofSeconds(8));
    }
    // 외부 주소는 고정하며 테스트에서만 로컬 응답 서버로 바꾼다.
    KakaoMapClient(KakaoMapProperties properties, ObjectMapper mapper, URI localEndpoint,
                   URI carEndpoint, Duration timeout) {
        this.properties=properties; this.mapper=mapper; this.localEndpoint=localEndpoint;
        this.carEndpoint=carEndpoint; this.timeout=timeout;
        this.http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public Location locate(String roadAddress) {
        if (roadAddress == null || roadAddress.isBlank() || roadAddress.length() > 300)
            throw invalidAddress();
        var root=get(localEndpoint.resolve("/v2/local/search/address.json?analyze_type=exact&query="
            + URLEncoder.encode(roadAddress, StandardCharsets.UTF_8)));
        var documents=root.path("documents");
        if (!documents.isArray() || documents.size()!=1) throw invalidAddress();
        var road=documents.get(0).path("road_address");
        // 첫 검색 결과를 무조건 채택하지 않고 도로명·건물번호까지 일치하는지 확인한다.
        if (!road.path("address_name").isTextual()
                || !normalize(roadAddress).equals(normalize(road.path("address_name").asText())))
            throw invalidAddress();
        try {
            if (!road.path("x").isTextual() || !road.path("y").isTextual()) throw invalidAddress();
            return new Location(road.path("address_name").asText(),
                Double.parseDouble(road.path("x").asText()), Double.parseDouble(road.path("y").asText()), Instant.now());
        } catch (IllegalArgumentException e) { throw invalidAddress(); }
    }

    public TravelTime travelTime(Location from, Location to, Transport transport) {
        if (from==null || to==null || transport==null) throw invalid();
        if (from.longitude()==to.longitude() && from.latitude()==to.latitude())
            throw failure("MAP_SAME_LOCATION", "같은 주소 안의 이동시간은 별도로 확인해야 해요.");
        JsonNode root;
        if (transport==Transport.CAR) {
            root=get(carEndpoint.resolve("/v1/directions?origin="+from.longitude()+","+from.latitude()
                +"&destination="+to.longitude()+","+to.latitude()+"&summary=true&alternatives=false&priority=RECOMMEND"));
        } else {
            root=get(localEndpoint.resolve("/v2/routing/publictraffic?start_x="+from.longitude()+"&start_y="+from.latitude()
                +"&end_x="+to.longitude()+"&end_y="+to.latitude()));
            if (!"OK".equals(root.path("status").asText())) throw noRoute();
        }
        var routes=root.path("routes");
        if (!routes.isArray() || routes.isEmpty() || routes.size()>100) throw noRoute();
        TravelTime fastest=null;
        for (var route:routes) {
            if (transport==Transport.CAR) {
                var code=route.path("result_code");
                if (!code.isIntegralNumber() || !code.canConvertToInt()) throw invalid();
                if (code.intValue()!=0) continue;
            }
            var summary=route.path(transport==Transport.CAR ? "summary" : "properties");
            var seconds=summary.path(transport==Transport.CAR ? "duration" : "totalTime");
            var meters=summary.path(transport==Transport.CAR ? "distance" : "totalDistance");
            if (!seconds.isIntegralNumber() || !seconds.canConvertToInt() || seconds.intValue()<=0
                    || seconds.intValue()>86400 || !meters.isIntegralNumber() || !meters.canConvertToInt()
                    || meters.intValue()<=0 || meters.intValue()>2000000) throw invalid();
            var candidate=new TravelTime(transport,seconds.intValue(),meters.intValue(),Instant.now());
            if (fastest==null || candidate.seconds()<fastest.seconds()) fastest=candidate;
        }
        if (fastest==null) throw noRoute();
        return fastest;
    }

    private JsonNode get(URI uri) {
        if (!properties.isConfigured()) throw failure("MAP_NOT_CONFIGURED", "지도 API 연결 설정이 필요해요.");
        try {
            var request=HttpRequest.newBuilder(uri).timeout(timeout)
                .header("Authorization", "KakaoAK "+properties.restApiKey()).GET().build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode()==401 || response.statusCode()==403)
                throw failure("MAP_AUTH", "지도 API 인증 또는 사용 권한을 확인해 주세요.");
            if (response.statusCode()==429) throw failure("MAP_RATE_LIMIT", "지도 API 사용량이 제한됐어요. 잠시 후 다시 시도해 주세요.");
            if (response.statusCode()!=200) throw failure("MAP_UNAVAILABLE", "지도 서비스에 연결하지 못했어요.");
            if (response.body().length()>2_000_000) throw invalid();
            var root=mapper.readTree(response.body());
            if (root==null || !root.isObject()) throw invalid();
            return root;
        } catch (RecommendationException e) { throw e; }
        catch (HttpTimeoutException e) { throw failure("MAP_TIMEOUT", "지도 응답 시간이 초과됐어요. 다시 시도해 주세요."); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw failure("MAP_INTERRUPTED", "지도 조회가 중단됐어요."); }
        catch (IOException e) { throw failure("MAP_UNAVAILABLE", "지도 서비스에 연결하지 못했어요."); }
        // 인증키나 공급자 오류 원문을 예외·로그에 포함하지 않는다. 자동 재호출도 하지 않는다.
        catch (RuntimeException e) { throw invalid(); }
    }
    private static String normalize(String address) {
        return address.strip().replaceFirst("^강원특별자치도\\s+", "강원 ")
            .replaceFirst("^강원도\\s+", "강원 ").replaceFirst("^경상북도\\s+", "경북 ")
            .replaceFirst("^부산광역시\\s+", "부산 ").replaceAll("\\s+", " ");
    }
    private static RecommendationException invalidAddress() { return failure("MAP_ADDRESS", "도로명 주소와 좌표를 정확히 확인하지 못했어요. 장소 주소를 확인해 주세요."); }
    private static RecommendationException noRoute() { return failure("MAP_NO_ROUTE", "선택한 이동수단으로 경로를 찾지 못했어요."); }
    private static RecommendationException invalid() { return failure("MAP_INVALID_RESPONSE", "지도 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요."); }
    private static RecommendationException failure(String code,String message) { return new RecommendationException(code,message); }
}
