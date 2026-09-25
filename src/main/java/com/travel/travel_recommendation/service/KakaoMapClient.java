package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.dto.PlaceCandidate;
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
    /**
     * summary는 대중교통일 때만 있는 노선 요약(SFR-06-04). 자동차 경로와 요약을 읽지 못한 경우는 null이다.
     * walkingEstimate는 걸어갈 만큼 가까워 카카오가 대중교통 경로를 주지 않은 구간을 직선거리로 추정했다는 뜻이다.
     */
    public record TravelTime(Transport transport, int seconds, int meters, Instant checkedAt, String summary, boolean walkingEstimate) {
        public TravelTime(Transport transport, int seconds, int meters, Instant checkedAt, String summary) {
            this(transport, seconds, meters, checkedAt, summary, false);
        }
        public TravelTime(Transport transport, int seconds, int meters, Instant checkedAt) {
            this(transport, seconds, meters, checkedAt, null);
        }
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

    public List<KakaoPlace> searchPlaces(String region,String type) {
        String city=searchWord(region);
        if(city==null) throw failure("MAP_REGION","여행지 지역 이름을 확인하지 못해 장소를 찾을 수 없어요.");
        if("ATTRACTION".equals(type)) return searchAttractions(city,region);
        String code, keyword; int limit;
        switch(type) {
            case "RESTAURANT" -> { code="FD6"; keyword="음식점"; limit=5; }
            case "ACCOMMODATION" -> { code="AD5"; keyword="숙소"; limit=3; }
            default -> throw invalid();
        }
        var root=get(localEndpoint.resolve("/v2/local/search/keyword.json?query="
            +URLEncoder.encode(city+" "+keyword,StandardCharsets.UTF_8)
            +"&category_group_code="+code+"&rect=124,33,132,39&size=15&page=1&sort=accuracy"));
        // 음식점은 일정의 끼니로 쓰므로 간식·술집·카페 분류는 받지 않는다(예: 아이스크림 가게가 저녁으로 들어가지 않게).
        return places(root,item->code.equals(item.path("category_group_code").asText())
            && !("RESTAURANT".equals(type) && PlaceCandidate.notForMeals(item.path("category_name").asText(""))),List.of(region),type,limit);
    }

    /**
     * 좌표 주변 radiusMeters 안의 음식점(FD6)을 정확도 순으로 최대 limit곳 찾는다. 일정에 넣을 관광지·숙소 근처에서 끼니를 고르기 위해 쓴다.
     * 간식·술집·카페와 여행지 지역(region) 밖의 곳은 받지 않는다.
     */
    public List<KakaoPlace> searchRestaurantsNear(double longitude,double latitude,String region,int radiusMeters,int limit) {
        if(region==null || radiusMeters<1 || radiusMeters>20000 || limit<1 || limit>15) throw invalid();
        if(!Double.isFinite(longitude) || !Double.isFinite(latitude) || longitude<124 || longitude>132 || latitude<33 || latitude>39)
            throw invalid();
        var root=get(localEndpoint.resolve("/v2/local/search/category.json?category_group_code=FD6&x="+longitude+"&y="+latitude
            +"&radius="+radiusMeters+"&sort=accuracy&size=15&page=1"));
        return places(root,item->"FD6".equals(item.path("category_group_code").asText())
            && !PlaceCandidate.notForMeals(item.path("category_name").asText("")),List.of(region),"RESTAURANT",limit);
    }

    /**
     * 관광지는 "○○ 명소"로 찾고 분류 이름이 '여행 > 관광,명소'인 곳만 받는다. 한옥마을·경기전처럼 대표 명소는
     * 분류 코드(AT4)가 비어 있는 경우가 많아 코드로 거르면 빠지기 때문이다. 정확도 순으로 최대 3쪽(45건)을 읽어
     * 앞쪽 15곳을 쓰고, "경기전 하마비"처럼 다른 후보 안의 시설은 뺀다.
     */
    private List<KakaoPlace> searchAttractions(String city,String region) {
        List<KakaoPlace> found=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int page=1;page<=3;page++) {
            var root=get(localEndpoint.resolve("/v2/local/search/keyword.json?query="
                +URLEncoder.encode(city+" 명소",StandardCharsets.UTF_8)+"&rect=124,33,132,39&size=15&page="+page+"&sort=accuracy"));
            for(var place:places(root,item->item.path("category_name").asText("").startsWith("여행 > 관광,명소"),List.of(region),"ATTRACTION",15))
                if(ids.add(place.sourceId())) found.add(place);
            if(root.path("meta").path("is_end").asBoolean(true)) break;
        }
        return withoutSubPlaces(found).stream().limit(15).toList();
    }

    /**
     * 불국사·전동성당 같은 사찰·성당은 카카오 분류가 '문화,예술 > 종교'라 "○○ 명소" 검색에 나오지 않아 따로 찾는다.
     * 사찰은 "○○ 사찰" 검색의 정확도 앞 2곳만 쓴다(예: 경주 → 불국사·석굴암).
     */
    public List<KakaoPlace> searchTemples(String region) {
        return searchReligious(region,"사찰","문화,예술 > 종교 > 불교 > 절,사찰",2);
    }

    /**
     * 성당은 "○○ 성당" 검색 앞쪽에 동네 본당도 섞여 나온다. 여기서는 후보만 돌려주고, 관광지로 볼지는
     * 관광공사 관광지와 위치가 맞는지로 호출하는 쪽({@link PlaceDiscoveryService})에서 판단한다.
     */
    public List<KakaoPlace> searchCathedrals(String region) {
        return searchReligious(region,"성당","문화,예술 > 종교 > 천주교 > 성당",15);
    }

    private List<KakaoPlace> searchReligious(String region,String keyword,String category,int limit) {
        String city=searchWord(region);
        if(city==null) throw failure("MAP_REGION","여행지 지역 이름을 확인하지 못해 장소를 찾을 수 없어요.");
        var root=get(localEndpoint.resolve("/v2/local/search/keyword.json?query="
            +URLEncoder.encode(city+" "+keyword,StandardCharsets.UTF_8)+"&rect=124,33,132,39&size=15&page=1&sort=accuracy"));
        // "전동성당 사제관"처럼 같은 분류로 나오는 부속 시설은 뺀다.
        return withoutSubPlaces(places(root,item->item.path("category_name").asText("").startsWith(category),List.of(region),"ATTRACTION",15))
            .stream().limit(limit).toList();
    }

    /** 다른 후보 이름 뒤에 한 칸 띄고 이어지는 곳(예: "경기전 하마비", "○○전당 공연장")은 그 후보 안의 시설로 보고 뺀다. */
    static List<KakaoPlace> withoutSubPlaces(List<KakaoPlace> places) {
        return places.stream().filter(p->places.stream().noneMatch(other->other!=p
            && p.name().startsWith(other.name()+" "))).toList();
    }

    /**
     * 여행 목적 시설을 전국에서 찾는다(예: "아쿠아리움"). 목적 시설 분류({@link #purposeCategory})만 받고, 주소가 regions 중
     * 하나로 시작하는 곳만 그 지역의 관광지 후보로 돌려준다. 시설 이름·주소·좌표만 확인하며 전시·체험 내용은 알 수 없다.
     */
    public List<KakaoPlace> searchNationwide(String query,List<String> regions) {
        if(query==null || query.isBlank() || query.length()>40 || regions.isEmpty()) throw invalid();
        var root=get(localEndpoint.resolve("/v2/local/search/keyword.json?query="
            +URLEncoder.encode(query.strip(),StandardCharsets.UTF_8)+"&rect=124,33,132,39&size=15&page=1&sort=accuracy"));
        return places(root,KakaoMapClient::purposeCategory,regions,"ATTRACTION",15);
    }

    /**
     * 관광공사 등 다른 출처에서 찾은 시설을 카카오 장소로 맞춘다. 좌표 1km 안에서 이름이 서로 포함되는 목적 시설 분류의
     * 장소 중 가장 가까운 곳을 고른다. 이름만 비슷한 주차장·음식점·축제는 분류에서 빠진다. 맞는 곳이 없으면 비어 있다.
     */
    public Optional<KakaoPlace> findNear(String name,double longitude,double latitude,String region) {
        if(name==null || name.isBlank() || name.length()>100 || region==null) throw invalid();
        if(!Double.isFinite(longitude) || !Double.isFinite(latitude) || longitude<124 || longitude>132 || latitude<33 || latitude>39)
            throw invalid();
        var root=get(localEndpoint.resolve("/v2/local/search/keyword.json?query="
            +URLEncoder.encode(name.strip(),StandardCharsets.UTF_8)+"&x="+longitude+"&y="+latitude
            +"&radius=1000&sort=distance&size=15&page=1"));
        String wanted=compactName(name);
        if(wanted.length()<2) return Optional.empty();
        return places(root,KakaoMapClient::purposeCategory,List.of(region),"ATTRACTION",15).stream()
            .filter(p->{ String found=compactName(p.name()); return found.length()>=2 && (found.contains(wanted) || wanted.contains(found)); })
            .findFirst();
    }

    /**
     * 목적 시설로 받을 카카오 분류. 관광명소(AT4)·문화시설(CT1)에 더해, 분류 코드가 비어 있는 체험장·체험마을·관광명소도 받는다
     * (예: 갯벌체험장은 코드 없이 '여행 > 체험여행'·'교육,학문 > 교육단체 > 체험학습장'으로만 분류된다).
     */
    static boolean purposeCategory(JsonNode item) {
        String code=item.path("category_group_code").asText("");
        if(Set.of("AT4","CT1").contains(code)) return true;
        String category=item.path("category_name").asText("");
        return code.isBlank() && (category.startsWith("여행 > 관광,명소") || category.startsWith("여행 > 체험여행")
            || category.startsWith("교육,학문 > 교육단체 > 체험학습장"));
    }

    /** 공백·기호를 뺀 이름. 출처마다 띄어쓰기가 달라도 같은 시설로 비교한다(예: "경포 아쿠아리움"과 "경포아쿠아리움"). */
    static String compactName(String name) {
        return name.replaceAll("[^가-힣A-Za-z0-9]","").toLowerCase(Locale.ROOT);
    }

    /** 주소가 어느 여행지 지역 안인지 찾는다. 시·도 약칭(예: "인천", "전남")도 같은 지역으로 본다. 없으면 null이다. */
    static String regionOf(String address,List<String> regions) {
        if(address==null || address.isBlank()) return null;
        String normalized=normalize(address);
        return regions.stream().filter(r->normalized.startsWith(normalize(r)+" ")).findFirst().orElse(null);
    }

    // 검색 결과를 검사해 후보로 바꾼다. 분류·주소(지역)·좌표·상세 링크가 맞지 않는 항목은 건너뛴다.
    private List<KakaoPlace> places(JsonNode root,java.util.function.Predicate<JsonNode> accepts,List<String> regions,String type,int limit) {
        var documents=root.path("documents");
        if(!documents.isArray() || documents.size()>15) throw invalid();
        List<KakaoPlace> result=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(var item:documents) {
            try {
                String id=requiredText(item,"id",20), name=requiredText(item,"place_name",200);
                String address=requiredText(item,"address_name",300);
                if(!id.matches("[1-9][0-9]{0,19}") || !accepts.test(item)) continue;
                String region=regionOf(address,regions);
                if(region==null) continue;
                String road=item.path("road_address_name").asText("");
                if(!road.isBlank()) {
                    if(road.length()>300 || !normalize(road).startsWith(normalize(region)+" ")) continue;
                    address=road;
                }
                var location=new Location(address,Double.parseDouble(requiredText(item,"x",40)),
                    Double.parseDouble(requiredText(item,"y",40)),Instant.now());
                String category=requiredText(item,"category_name",80);
                var url=URI.create(requiredText(item,"place_url",1000));
                if(!Set.of("http","https").contains(url.getScheme()) || !"place.map.kakao.com".equals(url.getHost())
                    || url.getUserInfo()!=null || url.getPort()!=-1 || !url.getPath().equals("/"+id)
                    || url.getQuery()!=null || url.getFragment()!=null || !ids.add(id)) continue;
                result.add(new KakaoPlace(id,type,name,region,address,category,location.longitude(),location.latitude(),
                    "https://place.map.kakao.com/"+id,LocalDateTime.now(ZoneOffset.UTC).withNano(0)));
                if(result.size()==limit) break;
            } catch(IllegalArgumentException ignored) {
                // 한 항목의 잘못된 주소·좌표로 다른 검증 가능한 후보까지 잃지 않는다.
            }
        }
        return List.copyOf(result);
    }
    private String requiredText(JsonNode item,String field,int max) {
        var value=item.path(field);
        if(!value.isTextual() || value.asText().isBlank() || value.asText().length()>max) throw new IllegalArgumentException();
        return value.asText().strip();
    }

    /**
     * 좌표가 속한 도시 이름을 버스 터미널 검색어로 돌려준다(예: 서울 송파구 → "서울", 강원 강릉시 → "강릉").
     * 사용자가 '내 위치로 찾기'를 누른 경우에만 부르며, 좌표는 저장하지 않는다.
     */
    public String cityKeyword(double longitude, double latitude) {
        if (!Double.isFinite(longitude) || !Double.isFinite(latitude) || longitude < 124 || longitude > 132 || latitude < 33 || latitude > 39)
            throw failure("MAP_LOCATION", "국내 위치만 찾을 수 있어요. 출발 도시를 직접 입력해 주세요.");
        var root=get(localEndpoint.resolve("/v2/local/geo/coord2regioncode.json?x="+longitude+"&y="+latitude));
        for (var document:root.path("documents")) {
            if (!"H".equals(document.path("region_type").asText())) continue;
            String first=document.path("region_1depth_name").asText("").strip();
            String second=document.path("region_2depth_name").asText("").strip();
            String keyword=cityKeyword(first,second);
            if (keyword!=null) return keyword;
        }
        throw failure("MAP_LOCATION", "현재 위치의 도시를 확인하지 못했어요. 출발 도시를 직접 입력해 주세요.");
    }
    // 특별시·광역시는 시 이름, 도 지역은 시·군 이름을 쓴다. "수원시 영통구"처럼 구가 붙으면 앞의 시만 쓴다.
    static String cityKeyword(String first, String second) {
        var metro=java.util.regex.Pattern.compile("^(.+?)(특별시|광역시|특별자치시)$").matcher(first);
        if (metro.matches()) return metro.group(1);
        if (second.isBlank()) return null;
        String city=second.split("\\s+")[0];
        var local=java.util.regex.Pattern.compile("^(.+?)[시군]$").matcher(city);
        return local.matches() && local.group(1).length()>=1 ? local.group(1) : null;
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
            if (!"OK".equals(root.path("status").asText())) {
                // 걸어갈 만큼 가까운 구간은 카카오가 대중교통 경로 없음(NO_RESULTS)으로 답한다. 이때만 도보 시간을 추정한다.
                if ("NO_RESULTS".equals(root.path("status").asText())) {
                    var walk=walkingEstimate(from,to);
                    if (walk!=null) return walk;
                }
                throw noRoute();
            }
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
            var candidate=new TravelTime(transport,seconds.intValue(),meters.intValue(),Instant.now(),
                transport==Transport.PUBLIC_TRANSIT ? transitSummary(route) : null);
            if (fastest==null || candidate.seconds()<fastest.seconds()) fastest=candidate;
        }
        if (fastest==null) throw noRoute();
        return fastest;
    }

    /** 도보로 대신할 최대 직선거리(m). 이보다 멀면 대중교통 경로가 없는 것으로 본다. */
    static final int WALK_MAX_METERS=1000;

    /**
     * 직선거리에 우회 30%를 더하고 시속 4km로 걸었을 때의 시간. 실제 보행 경로를 조회한 값이 아니므로
     * 요약에 추정임을 밝히고, 직선거리가 WALK_MAX_METERS를 넘으면 null이다.
     */
    static TravelTime walkingEstimate(Location from, Location to) {
        double lat1=Math.toRadians(from.latitude()), lat2=Math.toRadians(to.latitude());
        double dLat=lat2-lat1, dLon=Math.toRadians(to.longitude()-from.longitude());
        double h=Math.sin(dLat/2)*Math.sin(dLat/2)+Math.cos(lat1)*Math.cos(lat2)*Math.sin(dLon/2)*Math.sin(dLon/2);
        int straight=(int)Math.ceil(6371000*2*Math.asin(Math.min(1,Math.sqrt(h))));
        if (straight>WALK_MAX_METERS) return null;
        int meters=Math.max(1,(int)Math.ceil(straight*1.3));
        int seconds=Math.max(60,(int)Math.ceil(meters/(4000.0/3600)));
        String summary="도보 약 "+(seconds+59)/60+"분 · 직선 약 "+straight+"m라 대중교통 경로가 없어 걸어서 이동하는 것으로 추정했어요 (실제 보행 경로 확인 필요)";
        return new TravelTime(Transport.PUBLIC_TRANSIT,seconds,meters,Instant.now(),summary,true);
    }

    /**
     * 대중교통 경로 한 개를 "버스 202-1 (경포대 → 오죽헌) · 환승 0회 · 요금 1,530원"처럼 요약한다.
     * 걷는 구간은 빼고 탑승 구간만 순서대로 적는다. 형식이 예상과 다르면 요약 없이(null) 이동시간만 쓴다.
     */
    static String transitSummary(JsonNode route) {
        try {
            List<String> rides=new ArrayList<>();
            for (var step:route.path("steps")) {
                var p=step.path("properties");
                String type=p.path("type").asText("");
                if (!"BUS".equals(type) && !"SUBWAY".equals(type)) continue;
                Set<String> names=new LinkedHashSet<>();
                for (var vehicle:p.path("vehicles")) {
                    String name=vehicle.path("name").asText("").strip();
                    if (!name.isEmpty() && name.length()<=30) names.add(name);
                }
                var stops=p.path("stops");
                if (names.isEmpty() || !stops.isArray() || stops.size()<2) return null;
                String from=stops.get(0).path("name").asText("").strip(), to=stops.get(stops.size()-1).path("name").asText("").strip();
                if (from.isEmpty() || to.isEmpty() || from.length()>60 || to.length()>60) return null;
                rides.add(("BUS".equals(type)?"버스 ":"지하철 ")+String.join("·",names.stream().limit(3).toList())+" ("+from+" → "+to+")");
            }
            if (rides.isEmpty()) return null;
            var summary=new StringBuilder(String.join(" → ",rides));
            var transfers=route.path("properties").path("transfers");
            if (transfers.isIntegralNumber() && transfers.intValue()>=0) summary.append(" · 환승 ").append(transfers.intValue()).append("회");
            var fare=route.path("properties").path("fare").path("value");
            if (fare.isIntegralNumber() && fare.intValue()>0) summary.append(" · 요금 ").append(String.format(Locale.ROOT,"%,d",fare.intValue())).append("원");
            return summary.length()>500 ? null : summary.toString();
        } catch (RuntimeException e) { return null; }
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
    // 시·도 표기를 대표 이름으로 맞춰 비교한다. 예) "강원특별자치도 강릉시"와 카카오의 "강원 강릉시"를 같게 본다.
    static String normalize(String address) {
        return Provinces.normalizeAddress(address);
    }

    /**
     * 여행지 지역(region)으로 카카오 검색어에 쓸 지역 이름을 만든다. 조회 결과는 주소가 이 지역으로 시작하는 곳만 남긴다.
     * 예) "강원특별자치도 강릉시" → "강릉", "경기도 가평군" → "가평", "부산광역시" → "부산", "제주특별자치도" → "제주".
     */
    static String searchWord(String region) {
        if(region==null || region.isBlank()) return null;
        String[] parts=region.strip().split("\\s+");
        if(parts.length==1) return Provinces.canonical(parts[0]);
        if(parts.length!=2 || Provinces.canonical(parts[0])==null) return null;
        String city=parts[1];
        // 시·군 글자는 떼고 검색한다. 떼면 한 글자만 남는 이름은 그대로 둔다.
        return city.length()>2 && (city.endsWith("시") || city.endsWith("군")) ? city.substring(0,city.length()-1) : city;
    }
    private static RecommendationException invalidAddress() { return failure("MAP_ADDRESS", "도로명 주소와 좌표를 정확히 확인하지 못했어요. 장소 주소를 확인해 주세요."); }
    private static RecommendationException noRoute() { return failure("MAP_NO_ROUTE", "선택한 이동수단으로 경로를 찾지 못했어요."); }
    private static RecommendationException invalid() { return failure("MAP_INVALID_RESPONSE", "지도 응답을 확인하지 못했어요. 잠시 후 다시 시도해 주세요."); }
    private static RecommendationException failure(String code,String message) { return new RecommendationException(code,message); }
}
