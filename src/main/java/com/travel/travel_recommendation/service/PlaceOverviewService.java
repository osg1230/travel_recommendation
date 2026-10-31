package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정 초안에 들어간 관광지마다 한국관광공사 소개문을 찾아, AI가 그곳에서 해 볼 만한 활동을 쓸 근거로 넘긴다(2026-10-31).
 * 반려동물 동반 정보(PlacePetService)와 같은 방식으로 장소 이름을 관광공사에서 검색해 이름·위치가 맞는 곳이 하나일 때만 연결하고,
 * 결과는 장소 details_json에 보관해 다시 조회하지 않는다(PlaceOverviewDetails). 시간 제한을 넘기면 나머지 장소는 소개문 없이 둔다.
 */
@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceOverviewService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlaceOverviewService.class);
    private final PlaceRepository places;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    /** 한 초안에서 새로 조회하는 장소 수 상한. 장소마다 검색 1회·소개문 1회를 부르고, 개발 키의 하루 호출 한도는 웹 서버 전체가 같이 쓴다. */
    static final int MAX_LOOKUPS = 10;
    /** 다시 불러도 결과가 같은 오류(한도·인증·설정). 이때는 남은 장소도 조회하지 않는다. */
    private static final Set<String> STOP_CODES = Set.of("TOUR_QUOTA", "TOUR_AUTH", "TOUR_NOT_CONFIGURED");

    /** 장소 ID → 소개문. 일정의 관광 항목(저녁 관광 포함)만 보고, 소개문을 찾은 곳만 담는다. */
    public Map<Long, String> lookup(Input input, Result draft, Instant until) {
        if (!tour.configured()) return Map.of();
        Map<Long, PlaceCandidate> candidates = new HashMap<>();
        input.attractionsWithReserve().forEach(p -> candidates.putIfAbsent(p.placeId(), p));
        String city = input.places().destination().name();
        Map<Long, String> overviews = new HashMap<>(); var seen = new HashSet<Long>(); int lookups = 0;
        for (var day : draft.days()) for (var item : day.items()) {
            if (!"VISIT".equals(item.type()) || item.placeId() == null || !seen.add(item.placeId())) continue;
            var candidate = candidates.get(item.placeId());
            var place = places.findById(item.placeId()).orElse(null);
            if (candidate == null || place == null) continue;
            var saved = PlaceOverviewDetails.read(place, Instant.now(), mapper);
            if (saved == null) {
                if (lookups >= MAX_LOOKUPS || Instant.now().isAfter(until)) continue;
                lookups++;
                try { saved = find(candidate, city); }
                catch (RecommendationException error) {
                    log.warn("장소 소개문 찾기를 건너뜀 place={} ({})", candidate.placeId(), error.getCode());
                    if (STOP_CODES.contains(error.getCode())) return Map.copyOf(overviews);
                    continue;
                }
                save(candidate.placeId(), saved);
            }
            if (saved.found()) overviews.put(candidate.placeId(), saved.text());
        }
        return Map.copyOf(overviews);
    }

    /** 관광공사에서 장소를 하나로 찾으면 소개문을 조회한다. 하나로 찾지 못했거나 소개문이 없으면 '찾지 못함' 기록이다. */
    private PlaceOverviewDetails.Overview find(PlaceCandidate candidate, String city) {
        var found = tour.searchAttractions(candidate.name());
        var matches = found.stream().filter(f -> PlacePetService.matches(candidate, "ATTRACTION", city, f)).toList();
        // 이름이 같은 곳이 없으면 "보은 속리 정이품송"처럼 앞에 지명을 붙인 관광공사 제목을 본다(2026-10-31 브라우저 확인).
        if (matches.isEmpty()) matches = found.stream().filter(f -> prefixedTitle(candidate, f)).toList();
        if (matches.size() != 1) return new PlaceOverviewDetails.Overview(null, null, Instant.now());
        String contentId = matches.getFirst().id();
        return new PlaceOverviewDetails.Overview(contentId, tour.findOverview(contentId).orElse(null), Instant.now());
    }

    /** 이름이 같지 않아 더 가까워야 같은 장소로 보는 거리(m). 장소 사진(PlacePhotoService)과 같은 300m다. */
    static final double PREFIXED_TITLE_METERS = 300;

    /**
     * 관광공사 제목(괄호 설명은 뺌)이 앞에 지명을 붙이고 장소 이름으로 끝나며(예: 정이품송 → "보은 속리 정이품송"),
     * 좌표가 PREFIXED_TITLE_METERS 안인지. 이름이 같은 경우보다 느슨한 대조라 거리를 좁게 본다.
     */
    static boolean prefixedTitle(PlaceCandidate place, TourApiClient.SearchPlace found) {
        String name = PlaceOperatingService.compact(place.name());
        String title = PlaceOperatingService.compact(found.name().replaceAll("\\s*[\\[(（][^\\])）]*[\\])）]", ""));
        if (name.length() < 2 || title.equals(name) || !title.endsWith(name) || place.longitude() == null || place.latitude() == null
                || !Double.isFinite(found.longitude()) || !Double.isFinite(found.latitude())) return false;
        double dx = (place.longitude() - found.longitude()) * Math.cos(Math.toRadians(place.latitude())) * 111000;
        double dy = (place.latitude() - found.latitude()) * 111000;
        return Math.hypot(dx, dy) <= PREFIXED_TITLE_METERS;
    }

    // 외부 조회가 끝난 뒤 짧은 트랜잭션으로 장소 행을 잠그고 쓴다. 다른 상세정보(운영정보·사진·참고 가격)는 보존한다.
    private void save(long placeId, PlaceOverviewDetails.Overview overview) {
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> places.lockById(placeId).ifPresent(place -> {
            String json = PlaceOverviewDetails.write(place, overview, mapper);
            if (json != null) { place.updateDetails(json); places.flush(); }
        }));
    }
}
