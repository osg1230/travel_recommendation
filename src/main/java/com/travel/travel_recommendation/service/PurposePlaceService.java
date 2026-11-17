package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.PurposeSearch.PurposePlace;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 구체적 여행 목적(예: 상어 관람)에 맞는 시설을 찾아 추천 요청에 붙인다. 추천 작업이 후보를 고르기 전에 한 번 실행한다.
 * ① AI가 목적을 시설 검색어로 바꾸고 ② 관광공사 관광정보에서 먼저 찾아 카카오 장소로 맞춘 뒤 카카오 전국 검색으로 보충해
 * ③ 서비스 여행지 안에 있는 시설만 그 지역 관광지로 저장한다. 저장하는 장소는 모두 카카오에서 이름·주소·좌표를 확인한 곳이다.
 * 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 검색 → 저장을 나눈다. 검색이 실패해도 추천은 목적 시설 없이 그대로 진행한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PurposePlaceService {
    /** 여행지 한 곳에 붙이는 목적 시설 수와 전체 수의 상한. 장소 목록·AI 입력이 너무 길어지지 않게 한다. */
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PurposePlaceService.class);
    static final int MAX_PER_DESTINATION = 3, MAX_TOTAL = 12;
    /**
     * 검색어 변환(GPT)은 같은 목적이면 하루 동안 다시 묻지 않는다. 시설 검색 결과는 짧게 둔다. 카카오 장소는 조회 후 24시간 안에만
     * 일정에 쓸 수 있어, 오래 보관한 결과를 저장하면 곧 만료되는 장소가 되기 때문이다.
     */
    static final Duration KEYWORD_TTL = Duration.ofHours(24), PLACE_TTL = Duration.ofHours(6);
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final DestinationCandidateService destinations;
    private final OpenAiPurposeClient ai;
    private final KakaoMapClient maps;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final ObjectProvider<PurposePlaceService> self;
    private final DestinationFestivalService festivals;
    private final ExpiringCache<String, OpenAiPurposeClient.Terms> keywordCache = new ExpiringCache<>(KEYWORD_TTL, 500);
    private final ExpiringCache<String, List<KakaoPlace>> placeCache = new ExpiringCache<>(PLACE_TTL, 500);

    public record Task(UUID jobId, String requestJson, ConfirmedTravelConditions conditions, List<DestinationCandidate> destinations) {}
    public record Found(PurposeSearch search, List<KakaoPlace> places) {}
    /** 음식 축제를 확인한 결과. purpose가 있으면 여행 목적을 그것(예: 김밥 맛집 탐방)으로 바꾸고, message는 화면 안내다. */
    public record FestivalCheck(String purpose, String message) {}

    /** 보관한 검색어·검색 결과를 비운다. 외부 응답을 바꿔 가며 확인하는 테스트에서 쓴다. */
    public void clearCaches() { keywordCache.clear(); placeCache.clear(); }

    /** 이 작업에 목적 검색이 필요하면 찾아서 요청에 붙인다. 필요 없거나 이미 했으면 아무것도 하지 않는다. */
    public void enrich(UUID jobId) {
        var task = self.getObject().task(jobId);
        if (task == null) return;
        var found = search(task);
        self.getObject().save(task, found, foodFestival(task, found.search()));
    }

    /**
     * 음식 축제가 목적이면(예: 김밥 축제) 제안받은 여행지나 고른 지역에서 여행 기간에 열리는 그 음식 축제를 관광공사 행사 정보로 찾는다(2026-11-16).
     * 찾으면 목적을 그대로 두고 축제를 알린다. 못 찾으면 목적을 "김밥 맛집 탐방"으로 바꾸고 이유를 알린다.
     * AI가 축제를 아는 것처럼 써도, 열리는지는 외부 데이터로 확인한 것만 믿는다(AGENTS.md 9절). 음식 축제가 아니면 null이다.
     */
    FestivalCheck foodFestival(Task task, PurposeSearch search) {
        if (search == null || !search.festival() || !search.hasFood()) return null;
        var c = task.conditions();
        String food = search.food(), tour = food + " 맛집 탐방";
        // 못 찾은 경우 사용자가 결과부터 알 수 있게 "축제를 찾지 못했어요"를 앞에 두고, 대신 무엇을 하는지 붙인다(2026-11-17).
        String instead = " 대신 " + food + " 맛집을 찾아 먹거리 위주로 일정을 추천해요.";
        String region = c.region() == null ? "" : c.region().replaceAll("\\s+", "");
        var targets = task.destinations().stream().filter(d -> c.suggestedDestinationIds().contains(d.placeId())
            || (!region.isEmpty() && d.region().replaceAll("\\s+", "").contains(region))).toList();
        if (targets.isEmpty())
            return new FestivalCheck(tour, food + " 축제를 찾지 못했어요(축제가 열릴 지역을 정하지 못했어요)." + instead);
        if (!festivals.configured())
            return new FestivalCheck(tour, food + " 축제를 찾지 못했어요(관광공사 행사 정보를 조회할 수 없어요)." + instead);
        for (var destination : targets) {
            try {
                var festival = festivals.during(c, destination).stream().filter(f -> f.title().contains(food)).findFirst();
                if (festival.isPresent())
                    return new FestivalCheck(null, destination.name() + "에서 여행 기간에 열리는 '" + festival.get().title() + "'("
                        + festival.get().period() + ")" + ReadableText.particle(festival.get().title(), "을") + " 관광공사 행사 정보에서 찾았어요. 축제 일정은 주최 측 사정으로 바뀔 수 있어요.");
            } catch (RecommendationException e) {
                log.warn("음식 축제 조회를 건너뜀 destination={} ({})", destination.name(), e.getCode());
            }
        }
        String when = c.startDate() == null ? "앞으로 두 달 안에 열리는" : "여행 기간에 열리는";
        return new FestivalCheck(tour, food + " 축제를 찾지 못했어요(관광공사 행사 정보에서 " + when + " 축제 기준)." + instead);
    }

    @Transactional(readOnly = true, timeout = 10)
    public Task task(UUID jobId) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        var conditions = mapper.readValue(job.getRequestJson(), ConfirmedTravelConditions.class);
        if (conditions.purposeSearch() != null || conditions.travelPurpose() == null || conditions.travelPurpose().isBlank()) return null;
        return new Task(jobId, job.getRequestJson(), conditions, destinations.usable());
    }

    /**
     * 목적의 검색어·음식 이름. 같은 목적이면 하루 동안 다시 묻지 않는다. 지역 제안(RegionSuggestionService)이 음식 이름을 볼 때도
     * 이 메서드를 써서, 뒤이은 목적 시설 검색과 AI 호출을 나눠 쓴다(호출 수가 늘지 않는다).
     */
    public OpenAiPurposeClient.Terms terms(String purpose) {
        return keywordCache.get(purpose.strip(), () -> ai.terms(purpose));
    }

    /** 외부 호출만 하는 단계라 트랜잭션을 두지 않는다. */
    Found search(Task task) {
        var found = searchFacilities(task);
        // 음식 이름·축제 여부는 시설 검색 결과와 관계없이 남긴다. 지역 확인·음식점 찾기·일정 식사에서 쓴다(음식·축제 목적 계획).
        var terms = task.conditions().travelPurpose() == null ? null : keywordCache.peek(task.conditions().travelPurpose().strip());
        if (terms == null) return found;
        return new Found(found.search().withFood(terms.food(), terms.festival()), found.places());
    }

    private Found searchFacilities(Task task) {
        String purpose = task.conditions().travelPurpose();
        if (!ai.configured()) return new Found(result("SKIPPED", List.of(), List.of(), "AI 연결 설정이 없어 목적 시설을 찾지 않았어요.", List.of()), List.of());
        OpenAiPurposeClient.Terms terms;
        try { terms = keywordCache.get(purpose.strip(), () -> ai.terms(purpose)); }
        catch (RecommendationException e) { return new Found(result("FAILED", List.of(), List.of(), e.getMessage(), List.of()), List.of()); }
        var keywords = terms.keywords(); var themes = terms.themes();
        if (keywords.isEmpty())
            return new Found(result("SKIPPED", keywords, List.of(), "'" + purpose + "'은(는) 특정 시설로 찾을 목적이 아니라 목적 시설 검색을 하지 않았어요.", themes), List.of());
        var regions = task.destinations().stream().map(DestinationCandidate::region).toList();
        Map<String, KakaoPlace> bySource = new LinkedHashMap<>();
        try {
            for (String keyword : keywords)
                for (var place : placeCache.get(keyword + "|" + String.join(",", regions), () -> facilities(keyword, regions)))
                    bySource.putIfAbsent(place.sourceId(), place);
        } catch (RecommendationException e) {
            return new Found(result("FAILED", keywords, List.of(), e.getMessage(), themes), List.of());
        }
        // 여행지마다 검색 순서대로 최대 3곳, 전체 12곳까지만 쓴다.
        Map<String, Integer> perRegion = new HashMap<>(); List<KakaoPlace> kept = new ArrayList<>();
        for (var place : bySource.values()) {
            if (kept.size() == MAX_TOTAL || perRegion.merge(place.region(), 1, Integer::sum) > MAX_PER_DESTINATION) continue;
            kept.add(place);
        }
        if (kept.isEmpty()) return new Found(result("NONE", keywords, List.of(), "검색어(" + String.join(", ", keywords) + ")로 서비스 여행지 안의 시설을 찾지 못했어요.", themes), List.of());
        return new Found(result("FOUND", keywords, List.of(), null, themes), kept);
    }

    /**
     * 한 검색어로 시설을 찾는다. 관광공사 관광지·문화시설을 먼저 보고(제목 일치라 띄어쓰기를 뺀 검색어도 쓴다) 여행지 안의 시설을
     * 카카오 장소로 맞춘 뒤, 카카오 전국 검색 결과를 뒤에 붙인다. 관광공사 조회가 안 되면(키 없음·한도 등) 카카오만 쓴다.
     */
    List<KakaoPlace> facilities(String keyword, List<String> regions) {
        List<TourApiClient.SearchPlace> fromTour = new ArrayList<>();
        if (tour.configured()) {
            try {
                for (String query : new LinkedHashSet<>(List.of(keyword, keyword.replace(" ", ""))))
                    fromTour.addAll(tour.searchFacilities(query));
            } catch (RecommendationException e) {
                log.warn("관광공사 목적 시설 검색을 건너뜀 keyword={} ({})", keyword, e.getCode());
            }
        }
        List<KakaoPlace> found = new ArrayList<>();
        Map<String, Integer> perRegion = new HashMap<>(); Set<String> seen = new HashSet<>();
        for (var facility : fromTour) {
            String region = KakaoMapClient.regionOf(facility.address(), regions);
            if (region == null || !seen.add(facility.id()) || found.size() == MAX_TOTAL
                    || perRegion.getOrDefault(region, 0) == MAX_PER_DESTINATION) continue;
            maps.findNear(facility.name(), facility.longitude(), facility.latitude(), region).ifPresent(place -> {
                found.add(place); perRegion.merge(region, 1, Integer::sum);
            });
        }
        found.addAll(maps.searchNationwide(keyword, regions));
        return List.copyOf(found);
    }

    /** 검색하는 동안 요청이 처리되기 시작했거나 바뀌었으면 저장하지 않는다. 찾은 시설은 그 여행지의 관광지 후보로 저장한다. */
    @Transactional(timeout = 10)
    public void save(Task task, Found found) { save(task, found, null); }

    /** check는 음식 축제를 확인한 결과다(없으면 null). 목적을 바꿨으면 바뀐 목적으로 추천하고 이유를 안내 문구로 남긴다. */
    @Transactional(timeout = 10)
    public void save(Task task, Found found, FestivalCheck check) {
        var job = jobs.lockById(task.jobId()).orElse(null);
        if (job == null || !"QUEUED".equals(job.getStatus()) || !task.requestJson().equals(job.getRequestJson())) return;
        Map<String, DestinationCandidate> byRegion = new HashMap<>();
        task.destinations().forEach(d -> byRegion.putIfAbsent(d.region(), d));
        List<PurposePlace> saved = new ArrayList<>();
        for (var data : found.places()) {
            var destination = byRegion.get(data.region());
            if (destination == null) continue;
            var existing = places.findBySourceProviderAndSourcePlaceId("KAKAO", data.sourceId());
            Place place;
            if (existing.isPresent()) {
                place = existing.get();
                // 이미 다른 지역·유형(예: 음식점)으로 저장된 곳은 바꾸지 않는다.
                if (!place.isActive() || !place.getRegion().equals(data.region()) || !"ATTRACTION".equals(place.getPlaceType())) continue;
                // 보관해 둔 검색 결과가 더 최근 조회를 덮어쓰지 않게 한다.
                if (data.checkedAt().isAfter(place.getVerifiedAt())) place.refreshKakao(data);
            } else place = places.save(Place.fromKakao(data));
            saved.add(new PurposePlace(place.getId(), destination.placeId(), destination.name(), data.name(), data.address(), data.category(), data.url()));
        }
        places.flush();
        var search = found.search();
        String food = search.food(); boolean festival = search.festival();
        if ("FOUND".equals(search.status()) && saved.isEmpty())
            search = result("NONE", search.keywords(), List.of(), "찾은 시설을 여행지 관광지로 저장하지 못했어요.", search.themes());
        else if ("FOUND".equals(search.status())) search = result("FOUND", search.keywords(), saved, null, search.themes());
        search = search.withFood(food, festival);
        var conditions = task.conditions();
        if (check != null) {
            search = search.withMessage(check.message());
            if (check.purpose() != null) conditions = conditions.withPurpose(check.purpose(), false);
        }
        job.attachRequest(mapper.writeValueAsString(conditions.withPurposeSearch(search)));
        jobs.flush();
    }

    private static PurposeSearch result(String status, List<String> keywords, List<PurposePlace> places, String message, List<String> themes) {
        return new PurposeSearch(status, keywords, places, message, Instant.now(), themes);
    }
}
