package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 지역을 정하지 않고 원하는 여행을 말한 요청에, 그 여행을 실제로 할 수 있는 여행지를 찾아 붙인다. 추천 작업이 후보를 고르기 전에 한 번 실행한다.
 * ① AI가 시·군과 원하는 여행의 관광공사 분류를 제안하고 ② 서버가 법정동 코드로 실제 시·군인지, 그 분류의 관광공사 등록 장소가 있는지 확인한다.
 * ③ 서비스 여행지에 없던 시·군은 확인한 근거와 함께 여행지로 추가한다(2026-10-30 결정: 새 지역이고 여행할 수 있는 곳이면 DB에 추가).
 * 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 제안·확인 → 저장을 나눈다. 실패해도 추천은 기존 방식으로 그대로 진행한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class RegionSuggestionService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(RegionSuggestionService.class);
    /** 새로 추가한 여행지의 출처. 근거로 쓴 관광공사 정보(대한민국 구석구석)다. */
    static final String SOURCE_URL = "https://korean.visitkorea.or.kr/";
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final DestinationCandidateService destinations;
    private final DestinationProfileService profiles;
    private final OpenAiRegionClient ai;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final ObjectProvider<RegionSuggestionService> self;
    private final ObjectProvider<PurposePlaceService> purposes;
    private final KakaoMapClient maps;
    /** 음식으로 여행지를 확인할 때 필요한 음식점 수(2026-11-16 결정). 상호·분류에 그 음식이 들어간 카카오 음식점이다. */
    static final int FOOD_EVIDENCE = 3;

    public record Task(UUID jobId, String requestJson, ConfirmedTravelConditions conditions, List<DestinationCandidate> destinations) {}
    /**
     * 근거를 확인한 지역. existingId가 있으면 이미 서비스 여행지이고, 없으면 profile과 함께 새로 추가한다.
     * food·foodRestaurants는 음식으로 확인한 경우의 음식 이름과 찾은 음식점 수다(관광공사 분류로 확인했으면 빈 값·0).
     */
    record Checked(String region, Long existingId, Map<String, DestinationProfile.Category> profile, String food, int foodRestaurants) {
        Checked(String region, Long existingId, Map<String, DestinationProfile.Category> profile) { this(region, existingId, profile, "", 0); }
    }

    /** 이 작업에 지역 제안이 필요하면 확인한 여행지를 요청에 붙인다. 필요 없거나 이미 했으면 아무것도 하지 않는다. */
    public void enrich(UUID jobId) {
        var task = self.getObject().task(jobId);
        if (task == null) return;
        if (!ai.configured() || !tour.configured()) {
            self.getObject().save(task, result("SKIPPED", List.of(), List.of(), "AI·관광공사 연결 설정이 없어 지역을 제안받지 않았어요."));
            return;
        }
        OpenAiRegionClient.Suggestion suggestion;
        try { suggestion = ai.suggest(task.conditions()); }
        catch (RecommendationException e) {
            self.getObject().save(task, result("FAILED", List.of(), List.of(), e.getMessage()));
            return;
        }
        List<Long> ids = new ArrayList<>();
        // 새 여행지 이름이 기존 여행지나 이번에 추가한 여행지와 겹치지 않게 이름 목록을 함께 갱신한다.
        Set<String> names = new HashSet<>(task.destinations().stream().map(DestinationCandidate::name).toList());
        String food = food(task);
        for (var checked : check(task, suggestion, food)) {
            Long id = checked.existingId();
            if (id == null) {
                String name = name(checked.region(), names);
                id = add(checked, name, suggestion.categories());
                names.add(name);
            }
            if (id != null && !ids.contains(id)) ids.add(id);
        }
        String message = ids.isEmpty() ? "제안받은 지역(" + String.join(", ", suggestion.regions()) + ")에서 원하는 여행의 관광공사 등록 장소를 확인하지 못했어요." : null;
        self.getObject().save(task, result(ids.isEmpty() ? "NONE" : "FOUND", suggestion.categories(), ids, message));
    }

    @Transactional(readOnly = true, timeout = 10)
    public Task task(UUID jobId) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        var c = mapper.readValue(job.getRequestJson(), ConfirmedTravelConditions.class);
        // 지역을 정한 요청, 원하는 여행을 말하지 않아 AI가 계절에 맞춰 방향을 제안한 요청은 기존 여행지에서 고른다.
        if (c.regionSuggestion() != null || (c.region() != null && !c.region().isBlank())
                || c.travelPurpose() == null || c.travelPurpose().isBlank() || c.purposeSuggested()) return null;
        return new Task(jobId, job.getRequestJson(), c, destinations.usable());
    }

    /**
     * 제안받은 지역마다 근거를 확인한다. 이미 여행지면 보관한 "할 수 있는 것" 목록을, 아니면 관광공사에서 새로 센 목록을 본다.
     * 법정동 코드에 없는 지역이면 목록이 비어 빠진다. 원하는 여행의 분류에 등록 장소가 한 곳도 없으면 쓰지 않는다.
     * 사용자가 빼 달라고 한 지역은 AI에 알려 주고, 후보를 고를 때(DestinationCandidateService)도 다시 뺀다.
     */
    List<Checked> check(Task task, OpenAiRegionClient.Suggestion suggestion) { return check(task, suggestion, ""); }

    /**
     * food(목적의 음식 이름)가 있으면, 관광공사 분류로 근거가 없어도 그 음식 음식점이 FOOD_EVIDENCE곳 이상인 지역을 쓴다(2026-11-16).
     * 관광공사 분류에는 축제·음식이 없어, "김밥 축제"처럼 음식이 목적인 요청에서 AI가 제안한 김천이 빠졌기 때문이다.
     */
    List<Checked> check(Task task, OpenAiRegionClient.Suggestion suggestion, String food) {
        List<Checked> result = new ArrayList<>();
        if (suggestion.categories().isEmpty() && food.isEmpty()) return result;
        for (String region : suggestion.regions()) {
            var existing = task.destinations().stream().filter(d -> sameRegion(d.region(), region)).findFirst();
            try {
                var profile = existing.isPresent() ? profiles.profile(existing.get().placeId()) : profiles.build(region);
                Long existingId = existing.map(DestinationCandidate::placeId).orElse(null);
                if (DestinationProfile.count(profile, suggestion.categories()) > 0) { result.add(new Checked(region, existingId, profile)); continue; }
                if (food.isEmpty()) continue;
                int restaurants = maps.searchFoodRestaurants(region, food, 15).size();
                if (restaurants >= FOOD_EVIDENCE) result.add(new Checked(region, existingId, profile, food, restaurants));
            } catch (RecommendationException e) {
                log.warn("제안 지역을 확인하지 못함 region={} ({})", region, e.getCode());
            }
        }
        return result;
    }

    /** 새 시·군을 여행지로 추가하고 ID를 돌려준다. 다른 요청이 같은 시·군을 먼저 추가했으면 그 행을 쓴다. */
    private Long add(Checked checked, String name, List<String> categories) {
        try { return self.getObject().insert(checked, name, categories); }
        catch (DataIntegrityViolationException e) {
            return places.findBySourceProviderAndSourcePlaceId("TOURAPI", "REGION_" + checked.region())
                .filter(Place::isActive).map(Place::getId).orElse(null);
        }
    }

    /**
     * 추천 작업과 따로 커밋한다. 확인한 여행지는 이 요청이 실패해도 다음 추천에 그대로 쓴다. 근거 목록도 함께 보관한다.
     * 다른 여행지 행은 읽지 않는다(고유 키 조회와 추가만 한다). 다른 트랜잭션이 잠근 여행지 행을 기다리지 않게 하기 위해서다.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 10)
    public Long insert(Checked checked, String name, List<String> categories) {
        var existing = places.findBySourceProviderAndSourcePlaceId("TOURAPI", "REGION_" + checked.region());
        if (existing.isPresent()) return existing.get().isActive() ? existing.get().getId() : null;
        // 음식으로 확인한 지역은 분류를 '맛집'으로, 소개를 찾은 음식점 수로 적는다. 근거는 카카오 검색이고 메뉴를 확인한 것은 아니다.
        boolean byFood = !checked.food().isEmpty();
        var place = places.save(Place.verifiedDestination(name, checked.region(),
            byFood ? FOOD_THEME : subtype(checked.profile(), categories),
            byFood ? foodDescription(checked.food(), checked.foodRestaurants()) : description(checked.profile(), categories),
            byFood ? KAKAO_MAP_URL : SOURCE_URL, KoreaTime.now()));
        String json = DestinationProfile.write(place, checked.profile(), Instant.now(), mapper);
        if (json != null) place.updateDetails(json);
        places.flush();
        log.info("여행지 추가 region={} id={}", checked.region(), place.getId());
        return place.getId();
    }

    /** 검색하는 동안 요청이 처리되기 시작했거나 바뀌었으면 저장하지 않는다. */
    @Transactional(timeout = 10)
    public void save(Task task, RegionSuggestion suggestion) {
        var job = jobs.lockById(task.jobId()).orElse(null);
        if (job == null || !"QUEUED".equals(job.getStatus()) || !task.requestJson().equals(job.getRequestJson())) return;
        job.attachRequest(mapper.writeValueAsString(task.conditions().withRegionSuggestion(suggestion)));
        jobs.flush();
    }

    /** 두 지역 이름이 같은 시·도의 같은 시·군인지. 예) "강원도 강릉시"와 "강원특별자치도 강릉시", "부산"과 "부산광역시". */
    static boolean sameRegion(String a, String b) {
        String[] x = a.strip().split("\\s+"), y = b.strip().split("\\s+");
        String province = Provinces.canonical(x[0]);
        return province != null && province.equals(Provinces.canonical(y[0]))
            && Arrays.equals(Arrays.copyOfRange(x, 1, x.length), Arrays.copyOfRange(y, 1, y.length));
    }

    /**
     * 여행지 이름. 기존 여행지처럼 시·군 글자를 뗀다(예: 구례군 → 구례). 다른 시·도에 같은 이름의 여행지가 있으면
     * (예: 강원 고성군과 경남 고성군) 헷갈리지 않게 시·군 이름을 그대로 쓴다.
     */
    static String name(String region, Set<String> usedNames) {
        String city = region.strip().split("\\s+")[1];
        String shortName = city.length() > 2 ? city.substring(0, city.length() - 1) : city;
        return usedNames.contains(shortName) ? city : shortName;
    }

    /**
     * 여행지 분류(여행 취향 이름). 버튼으로 고른 취향과 맞출 때 쓴다. 여행지로 추가한 이유인 원하는 여행의 분류(등록 장소가 있는 것)를
     * 여행 취향으로 바꾼다. 예) 섬·해변 → 바다, 산·계곡 → 자연, 체험마을·박물관 → 체험·액티비티, 문화·역사.
     * 시·군마다 사찰·박물관·체험 시설은 대부분 몇 곳씩 있어 장소 수로 정하면 모든 곳이 같은 분류가 되기 때문이다.
     */
    static String subtype(Map<String, DestinationProfile.Category> profile, List<String> categories) {
        String themes = found(profile, categories).stream().map(WishCategories::theme).distinct().limit(3)
            .collect(Collectors.joining(","));
        return themes.isEmpty() ? null : themes;
    }

    /**
     * 여행지 소개. 확인한 근거 분류마다 대표 장소 이름을 하나씩 돌아가며 3개 고른다(한 분류의 이름만 나오지 않게).
     * 예) "노고단·지리산국립공원·반야봉 등 산·오름·봉우리, 국립공원을 둘러볼 수 있는 지역입니다."
     */
    static String description(Map<String, DestinationProfile.Category> profile, List<String> categories) {
        var found = found(profile, categories);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < DestinationProfile.NAMES && names.size() < 3; i++)
            for (String code : found) {
                var list = profile.get(code).names();
                if (i < list.size() && names.size() < 3 && !names.contains(list.get(i))) names.add(list.get(i));
            }
        String labels = String.join(", ", found.stream().map(WishCategories.NAMES::get).limit(3).toList());
        return String.join("·", names) + " 등 " + labels + ReadableText.particle(labels, "을") + " 둘러볼 수 있는 지역입니다.";
    }
    /** 음식으로 확인한 여행지의 분류(여행 취향 이름)와 근거 출처. */
    static final String FOOD_THEME = "맛집", KAKAO_MAP_URL = "https://map.kakao.com/";

    /** 음식으로 확인한 여행지의 소개. 예) "김밥 음식점 12곳이 있는 지역입니다(카카오 지도 검색, 메뉴는 확인하지 않음)." */
    static String foodDescription(String food, int restaurants) {
        return food + " 음식점 " + restaurants + "곳" + (restaurants >= 15 ? " 이상" : "") + "이 있는 지역입니다(카카오 지도 검색, 메뉴는 확인하지 않음).";
    }

    /** 목적의 음식 이름. 목적 검색어 AI의 결과를 쓰고(PurposePlaceService와 함께 씀), 실패하면 빈 문자열이다. */
    private String food(Task task) {
        try { return purposes.getObject().terms(task.conditions().travelPurpose()).food(); }
        catch (RecommendationException e) {
            log.warn("목적 음식 이름을 확인하지 못함 ({})", e.getCode());
            return "";
        }
    }

    /** 원하는 여행의 분류 중 등록 장소가 있는 것. */
    private static List<String> found(Map<String, DestinationProfile.Category> profile, List<String> categories) {
        return categories.stream().filter(code -> DestinationProfile.count(profile, List.of(code)) > 0).toList();
    }

    private static RegionSuggestion result(String status, List<String> categories, List<Long> ids, String message) {
        return new RegionSuggestion(status, categories, ids, message, Instant.now());
    }
}
