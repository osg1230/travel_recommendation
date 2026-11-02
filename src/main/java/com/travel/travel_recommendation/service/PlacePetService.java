package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.dto.PlacePetInfo;
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
 * 반려동물 동반 여행에서 일정 초안에 들어간 장소마다 한국관광공사 반려동물 동반 정보를 찾는다.
 * 운영정보와 같은 방식으로 장소 이름을 관광공사에서 검색해 이름·도로명·유형이 하나로 맞는 곳만 연결하고,
 * 결과는 장소 details_json에 보관해 7일 동안 다시 조회하지 않는다. 일정 작업 안에서 실행되므로 시간 제한을 넘기면 나머지는 '정보 없음'으로 둔다.
 */
@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlacePetService {
    private final PlaceRepository places;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    /** 한 초안에서 새로 조회하는 장소 수 상한. 장소마다 검색 1회·동반 정보 1회를 부른다. */
    static final int MAX_LOOKUPS = 12;

    public List<PetCheck> lookup(Input input, Result draft, Instant deadline) {
        var pool = new ArrayList<>(input.attractionsWithReserve());
        pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var checks = new ArrayList<PetCheck>(); var seen = new HashSet<Long>(); int lookups = 0;
        for (var day : draft.days()) for (var item : day.items()) {
            if (!Set.of("VISIT", "MEAL", "STAY").contains(item.type()) || item.placeId() == null || !seen.add(item.placeId())) continue;
            var candidate = pool.stream().filter(p -> p.placeId().equals(item.placeId())).findFirst().orElse(null);
            if (candidate == null) continue;
            String type = input.places().restaurants().contains(candidate) ? "RESTAURANT"
                : input.places().accommodations().contains(candidate) ? "ACCOMMODATION" : "ATTRACTION";
            var place = places.findById(candidate.placeId()).orElse(null);
            var stored = place == null ? null : PlacePetDetails.read(place, mapper);
            PlacePetInfo info = stored;
            // 관광공사에서 얼마 전에 찾지 못한 장소는 초안마다 다시 검색하지 않는다.
            if (!PlacePetDetails.fresh(stored, Instant.now()) && place != null && PlacePetDetails.missedRecently(place, Instant.now(), mapper)) info = null;
            else if (!PlacePetDetails.fresh(stored, Instant.now())) {
                info = lookups < MAX_LOOKUPS && Instant.now().isBefore(deadline) ? find(candidate, type, input.places().destination().name()) : null;
                lookups++;
            }
            checks.add(new PetCheck(candidate.placeId(), item.name(), info));
        }
        return List.copyOf(checks);
    }

    /** 관광공사에서 장소를 하나로 찾으면 동반 정보를 조회해 저장한다. 찾지 못하거나 오류가 나면 null이다. */
    private PlacePetInfo find(PlaceCandidate candidate, String type, String city) {
        try {
            var matches = tour.search(candidate.name()).stream().filter(p -> matches(candidate, type, city, p)).toList();
            if (matches.size() != 1) { saveMiss(candidate.placeId()); return null; }
            String contentId = matches.getFirst().id();
            var info = tour.findPetInfo(contentId).orElse(PlacePetInfo.none(contentId));
            save(candidate.placeId(), info);
            return info;
        } catch (RecommendationException error) { return null; }
    }

    /** 이름이 같다고 볼 때 허용하는 좌표 거리(m). 해변·호수처럼 넓은 장소는 카카오와 관광공사의 대표 지점이 다르다. */
    static final double SAME_PLACE_METERS = 1500;

    /**
     * 운영정보(PlaceOperatingService.matches)보다 느슨한 대조. 해변·호수 같은 자연 장소는 카카오 주소가 지번이거나 도로명 번호가 달라
     * 도로명이 일치하지 않는 경우가 많다. 유형이 맞고 이름이 같으며(관광공사 제목의 괄호 설명은 뺀다),
     * 도로명 주소가 같거나 좌표가 SAME_PLACE_METERS 안이면 같은 장소로 본다.
     */
    static boolean matches(PlaceCandidate place, String type, String city, TourApiClient.SearchPlace found) {
        var types = switch (type) {
            case "ATTRACTION" -> Set.of("12", "14", "28");
            case "RESTAURANT" -> Set.of("39");
            default -> Set.of("32");
        };
        if (!types.contains(found.type())) return false;
        String name = PlaceOperatingService.compact(place.name());
        String title = PlaceOperatingService.compact(found.name().replaceAll("\\s*[\\[(（][^\\])）]*[\\])）]", ""));
        String cityName = PlaceOperatingService.compact(city == null ? "" : city);
        boolean sameName = name.equals(title) || (cityName + name).equals(title) || name.equals(cityName + title);
        if (!sameName) return false;
        String road = PlaceOperatingService.road(place.address()), other = PlaceOperatingService.road(found.address());
        if (!road.isEmpty() && road.equals(other)) return true;
        if (place.longitude() == null || place.latitude() == null) return false;
        double dx = (place.longitude() - found.longitude()) * Math.cos(Math.toRadians(place.latitude())) * 111000;
        double dy = (place.latitude() - found.latitude()) * 111000;
        return Math.hypot(dx, dy) <= SAME_PLACE_METERS;
    }

    // 외부 조회가 끝난 뒤 짧은 트랜잭션으로 장소 행을 잠그고 쓴다. 다른 상세정보(운영정보·참고 가격)는 보존한다.
    private void save(long placeId, PlacePetInfo info) {
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> places.lockById(placeId).ifPresent(place -> {
            place.updateDetails(PlacePetDetails.write(place, info, mapper));
            places.flush();
        }));
    }
    private void saveMiss(long placeId) {
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> places.lockById(placeId).ifPresent(place -> {
            place.updateDetails(PlacePetDetails.writeMiss(place, Instant.now(), mapper));
            places.flush();
        }));
    }
}
