package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.dto.RegionalFestival;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 추천 후보 여행지에서 여행 시기에 열리는 축제를 찾는다. 사용자가 축제를 직접 고르지 않아도 추천 이유와 일정에 넣기 위해서다.
 * 축제는 홈 화면과 같은 관광공사 조회 결과(FestivalService, 6시간 보관)를 쓰고, 조회에 실패하면 축제 없이 추천을 계속한다.
 */
@Service
@RequiredArgsConstructor
public class DestinationFestivalService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(DestinationFestivalService.class);
    /** 여행지 한 곳에 붙이는 축제 수. 추천 카드와 AI 입력이 길어지지 않게 한다. */
    static final int MAX_PER_DESTINATION = 3;
    private final FestivalService festivals;

    /** 여행지 ID → 그 여행지의 축제(시작일 순, 최대 3개). 축제가 없는 여행지는 넣지 않는다. */
    public Map<Long, List<RegionalFestival>> find(ConfirmedTravelConditions conditions, List<DestinationCandidate> candidates) {
        Map<Long, List<RegionalFestival>> result = new LinkedHashMap<>();
        if (!festivals.configured()) return result;
        for (var candidate : candidates) {
            try {
                var found = inDestination(candidate).stream()
                    .filter(f -> duringTrip(f, conditions.startDate(), conditions.endDate()))
                    .limit(MAX_PER_DESTINATION).toList();
                if (!found.isEmpty()) result.put(candidate.placeId(), found);
            } catch (RecommendationException e) {
                log.warn("여행지 축제 조회를 건너뜀 destination={} ({})", candidate.name(), e.getCode());
            }
        }
        return result;
    }

    /** 그 여행지 주소의 축제만 고른다. 시·도 전체 축제에서 주소에 시·군 이름(없으면 여행지 이름)이 들어 있는 것만 남긴다. */
    private List<RegionalFestival> inDestination(DestinationCandidate candidate) {
        String[] parts = candidate.region().strip().split("\\s+");
        String provinceName = Provinces.canonical(parts[0]);
        if (provinceName == null) return List.of();
        var province = FestivalService.PROVINCES.stream()
            .filter(p -> provinceName.equals(Provinces.canonical(p.regionName()))).findFirst().orElse(null);
        if (province == null) return List.of();
        String local = parts.length > 1 ? parts[1] : candidate.name();
        return festivals.findFestivals(province).stream().filter(f -> f.address().contains(local)).toList();
    }

    /** 여행 날짜가 정해졌으면 그 기간과 겹치는 축제만 쓴다. 미정이면 진행 중이거나 곧 열리는 축제를 모두 쓴다. */
    static boolean duringTrip(RegionalFestival festival, LocalDate start, LocalDate end) {
        if (start == null || end == null) return true;
        return !festival.startDate().isAfter(end) && !festival.endDate().isBefore(start);
    }
}
