package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.PurposeSearch.PurposePlace;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.*;
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
 * ① AI가 목적을 시설 검색어로 바꾸고 ② 카카오에서 전국을 검색해 ③ 서비스 여행지 안에 있는 시설만 그 지역 관광지로 저장한다.
 * 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 검색 → 저장을 나눈다. 검색이 실패해도 추천은 목적 시설 없이 그대로 진행한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PurposePlaceService {
    /** 여행지 한 곳에 붙이는 목적 시설 수와 전체 수의 상한. 장소 목록·AI 입력이 너무 길어지지 않게 한다. */
    static final int MAX_PER_DESTINATION = 3, MAX_TOTAL = 12;
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final DestinationCandidateService destinations;
    private final OpenAiPurposeClient ai;
    private final KakaoMapClient maps;
    private final ObjectMapper mapper;
    private final ObjectProvider<PurposePlaceService> self;

    public record Task(UUID jobId, String requestJson, ConfirmedTravelConditions conditions, List<DestinationCandidate> destinations) {}
    public record Found(PurposeSearch search, List<KakaoPlace> places) {}

    /** 이 작업에 목적 검색이 필요하면 찾아서 요청에 붙인다. 필요 없거나 이미 했으면 아무것도 하지 않는다. */
    public void enrich(UUID jobId) {
        var task = self.getObject().task(jobId);
        if (task == null) return;
        var found = search(task);
        self.getObject().save(task, found);
    }

    @Transactional(readOnly = true, timeout = 10)
    public Task task(UUID jobId) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        var conditions = mapper.readValue(job.getRequestJson(), ConfirmedTravelConditions.class);
        if (conditions.purposeSearch() != null || conditions.travelPurpose() == null || conditions.travelPurpose().isBlank()) return null;
        return new Task(jobId, job.getRequestJson(), conditions, destinations.usable());
    }

    /** 외부 호출만 하는 단계라 트랜잭션을 두지 않는다. */
    Found search(Task task) {
        String purpose = task.conditions().travelPurpose();
        if (!ai.configured()) return new Found(result("SKIPPED", List.of(), List.of(), "AI 연결 설정이 없어 목적 시설을 찾지 않았어요."), List.of());
        List<String> keywords;
        try { keywords = ai.keywords(purpose); }
        catch (RecommendationException e) { return new Found(result("FAILED", List.of(), List.of(), e.getMessage()), List.of()); }
        if (keywords.isEmpty())
            return new Found(result("SKIPPED", keywords, List.of(), "'" + purpose + "'은(는) 특정 시설로 찾을 목적이 아니라 목적 시설 검색을 하지 않았어요."), List.of());
        var regions = task.destinations().stream().map(DestinationCandidate::region).toList();
        Map<String, KakaoPlace> bySource = new LinkedHashMap<>();
        try {
            for (String keyword : keywords)
                for (var place : maps.searchNationwide(keyword, regions)) bySource.putIfAbsent(place.sourceId(), place);
        } catch (RecommendationException e) {
            return new Found(result("FAILED", keywords, List.of(), e.getMessage()), List.of());
        }
        // 여행지마다 검색 순서대로 최대 3곳, 전체 12곳까지만 쓴다.
        Map<String, Integer> perRegion = new HashMap<>(); List<KakaoPlace> kept = new ArrayList<>();
        for (var place : bySource.values()) {
            if (kept.size() == MAX_TOTAL || perRegion.merge(place.region(), 1, Integer::sum) > MAX_PER_DESTINATION) continue;
            kept.add(place);
        }
        if (kept.isEmpty()) return new Found(result("NONE", keywords, List.of(), "검색어(" + String.join(", ", keywords) + ")로 서비스 여행지 안의 시설을 찾지 못했어요."), List.of());
        return new Found(result("FOUND", keywords, List.of(), null), kept);
    }

    /** 검색하는 동안 요청이 처리되기 시작했거나 바뀌었으면 저장하지 않는다. 찾은 시설은 그 여행지의 관광지 후보로 저장한다. */
    @Transactional(timeout = 10)
    public void save(Task task, Found found) {
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
                place.refreshKakao(data);
            } else place = places.save(Place.fromKakao(data));
            saved.add(new PurposePlace(place.getId(), destination.placeId(), destination.name(), data.name(), data.address(), data.category(), data.url()));
        }
        places.flush();
        var search = found.search();
        if ("FOUND".equals(search.status()) && saved.isEmpty())
            search = result("NONE", search.keywords(), List.of(), "찾은 시설을 여행지 관광지로 저장하지 못했어요.");
        else if ("FOUND".equals(search.status())) search = result("FOUND", search.keywords(), saved, null);
        job.attachRequest(mapper.writeValueAsString(task.conditions().withPurposeSearch(search)));
        jobs.flush();
    }

    private static PurposeSearch result(String status, List<String> keywords, List<PurposePlace> places, String message) {
        return new PurposeSearch(status, keywords, places, message, Instant.now());
    }
}
