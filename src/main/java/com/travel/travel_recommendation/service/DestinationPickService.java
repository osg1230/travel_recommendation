package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.AiJobRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 자연어 요청에서 여행지 후보가 5곳보다 많으면, AI가 여행지마다 "할 수 있는 것" 근거(관광공사 등록 장소)를 보고 조건에 맞는 5곳을 고른다.
 * 추천 작업이 후보를 고르기 전에 지역 제안(RegionSuggestionService)·목적 시설 검색 다음으로 한 번 실행하고, 고른 결과는 요청 스냅샷에 남긴다.
 * 근거는 보관한 목록만 읽고(관광공사를 새로 부르지 않음) 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 고르기 → 저장을 나눈다.
 * 실패해도 추천은 기존 기준(제안 여행지·여행 취향·근거 가짓수)으로 후보를 골라 그대로 진행한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class DestinationPickService {
    /** GPT에 보내는 여행지 수 상한. 여행지가 늘어도 입력이 너무 길어지지 않게 근거가 많은 곳부터 자른다. */
    static final int MAX_OPTIONS = 50;
    /** 원하는 여행의 분류가 없을 때 여행지마다 보내는 근거 분류 수(장소가 많은 순). 분류마다 대표 이름은 NAMES개다. */
    static final int GENERAL_EVIDENCE = 4, NAMES = 3;
    private final AiJobRepository jobs;
    private final DestinationCandidateService destinations;
    private final DestinationProfileService profiles;
    private final OpenAiDestinationPickClient ai;
    private final ObjectMapper mapper;
    private final ObjectProvider<DestinationPickService> self;

    public record Task(UUID jobId, String requestJson, ConfirmedTravelConditions conditions, List<DestinationCandidate> destinations) {}

    /** 이 작업에 후보 고르기가 필요하면 고른 여행지를 요청에 붙인다. 필요 없거나 이미 했으면 아무것도 하지 않는다. */
    public void enrich(UUID jobId) {
        var task = self.getObject().task(jobId);
        if (task == null) return;
        if (!ai.configured()) { self.getObject().save(task, result("SKIPPED", List.of(), "AI 연결 설정이 없어 기존 기준으로 후보를 골랐어요.")); return; }
        var ids = task.destinations().stream().map(DestinationCandidate::placeId).toList();
        var options = options(task.conditions(), task.destinations(), profiles.saved(ids));
        try {
            var picked = ai.pick(task.conditions(), options);
            self.getObject().save(task, picked.isEmpty() ? result("NONE", List.of(), "AI가 고른 여행지가 없어 기존 기준으로 후보를 골랐어요.")
                : result("PICKED", picked, null));
        } catch (RecommendationException e) {
            self.getObject().save(task, result("FAILED", List.of(), e.getMessage()));
        }
    }

    @Transactional(readOnly = true, timeout = 10)
    public Task task(UUID jobId) {
        var job = jobs.findById(jobId).orElse(null);
        if (job == null || !"RECOMMENDATION".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        var c = mapper.readValue(job.getRequestJson(), ConfirmedTravelConditions.class);
        // 문장으로 입력한 요청만 AI가 고른다. 조건 선택(버튼) 입력은 근거 가짓수로 서버가 고른다(DestinationCandidateService).
        if (c.destinationPick() != null || !"TEXT".equals(c.inputMode())) return null;
        var matching = destinations.matching(c);
        if (matching.size() <= DestinationCandidateService.MAX_CANDIDATES) return null;
        return new Task(jobId, job.getRequestJson(), c, matching);
    }

    /**
     * GPT에 보낼 여행지 목록. 원하는 여행의 분류(지역 제안에서 받은 것)가 있으면 그 분류의 등록 장소 수·대표 이름을,
     * 없으면 여행지마다 장소가 많은 분류 몇 개를 근거로 붙인다. 목적 시설 이름도 붙인다.
     * 많으면 제안 여행지 → 목적 시설이 있는 곳 → 근거 가짓수가 많은 곳 순서로 MAX_OPTIONS곳까지 보낸다.
     */
    static List<OpenAiDestinationPickClient.Option> options(ConfirmedTravelConditions c, List<DestinationCandidate> matching,
                                                            Map<Long, Map<String, DestinationProfile.Category>> profiles) {
        var wish = c.wishCategories();
        List<OpenAiDestinationPickClient.Option> options = new ArrayList<>();
        for (var d : matching) {
            var profile = profiles.getOrDefault(d.placeId(), Map.of());
            // 원하는 여행의 분류는 모두(최대 6개), 없으면 장소가 많은 분류 몇 개를 보낸다.
            var evidence = DestinationProfile.evidence(profile, wish, wish.isEmpty() ? GENERAL_EVIDENCE : wish.size(), NAMES);
            var facilities = c.purposeSearch() == null ? List.<String>of()
                : c.purposeSearch().placesIn(d.placeId()).stream().map(PurposeSearch.PurposePlace::name).toList();
            options.add(new OpenAiDestinationPickClient.Option(d.placeId(), d.name(), d.region(), Objects.toString(d.description(), ""),
                evidence, facilities));
        }
        var suggested = c.suggestedDestinationIds();
        options.sort(Comparator.comparingInt((OpenAiDestinationPickClient.Option o) -> suggested.contains(o.id()) ? 0 : 1)
            .thenComparingInt(o -> o.facilities().isEmpty() ? 1 : 0)
            .thenComparingInt(o -> wish.isEmpty() ? 0 : -o.evidence().size())
            .thenComparingLong(OpenAiDestinationPickClient.Option::id));
        return List.copyOf(options.subList(0, Math.min(MAX_OPTIONS, options.size())));
    }

    /** 고르는 동안 요청이 처리되기 시작했거나 바뀌었으면 저장하지 않는다. */
    @Transactional(timeout = 10)
    public void save(Task task, DestinationPick pick) {
        var job = jobs.lockById(task.jobId()).orElse(null);
        if (job == null || !"QUEUED".equals(job.getStatus()) || !task.requestJson().equals(job.getRequestJson())) return;
        job.attachRequest(mapper.writeValueAsString(task.conditions().withDestinationPick(pick)));
        jobs.flush();
    }

    private static DestinationPick result(String status, List<Long> ids, String message) {
        return new DestinationPick(status, ids, message, Instant.now());
    }
}
