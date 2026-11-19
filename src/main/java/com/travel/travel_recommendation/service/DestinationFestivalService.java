package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.dto.RegionalFestival;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 추천 후보 여행지에서 여행 시기에 열리는 축제를 찾는다. 사용자가 축제를 직접 고르지 않아도 추천 이유와 일정에 넣기 위해서다.
 * 보통은 홈 화면과 같은 관광공사 조회 결과(FestivalService, 6시간 보관)를 쓰고, 사용자가 축제를 찾는 요청이면 여행 기간으로 따로 조회한다(during).
 * 조회에 실패하면 축제 없이 추천을 계속한다.
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
        if (!festivals.configured() || otherSeason(conditions)) return result;
        boolean requested = festivalRequested(conditions);
        for (var candidate : candidates) {
            try {
                // 축제를 찾는 요청이면 여행 기간 축제를 따로 조회하고, 아니면 홈 화면 탭과 같은 목록(60일·15개)에서 고른다.
                var found = (requested ? during(conditions, candidate) : inDestination(candidate, festivals::findFestivals).stream()
                    .filter(f -> duringTrip(f, conditions.startDate(), conditions.endDate())).toList())
                    .stream().limit(MAX_PER_DESTINATION).toList();
                if (!found.isEmpty()) result.put(candidate.placeId(), found);
            } catch (RecommendationException e) {
                log.warn("여행지 축제 조회를 건너뜀 destination={} ({})", candidate.name(), e.getCode());
            }
        }
        return result;
    }

    public boolean configured() { return festivals.configured(); }

    /**
     * 축제를 찾는 요청일 때, 여행지 한 곳에서 여행 기간에 열리는 축제(시작일 순, 2026-11-17).
     * 홈 화면 탭 목록(오늘부터 60일·15개)을 쓰지 않고 여행 날짜로 따로 조회해, 석 달 뒤 여행이나 축제가 많은 시·도에서도 놓치지 않게 한다.
     * 날짜가 미정이면 오늘부터 60일 안에 열리는 축제다. 음식 축제 확인(PurposePlaceService)과 추천 카드가 같이 쓴다.
     */
    public List<RegionalFestival> during(ConfirmedTravelConditions conditions, DestinationCandidate candidate) {
        LocalDate from = conditions.startDate() != null ? conditions.startDate() : festivals.today();
        LocalDate until = conditions.endDate() != null ? conditions.endDate() : from.plusDays(FestivalService.LOOKAHEAD_DAYS);
        return inDestination(candidate, province -> festivals.findFestivalsBetween(province, from, until));
    }

    /** 여행 목적이 축제인지(문장 분석의 목적 검색이 축제로 본 경우, 예: "김밥 축제", "불꽃축제"). */
    static boolean festivalRequested(ConfirmedTravelConditions conditions) {
        return conditions.purposeSearch() != null && conditions.purposeSearch().festival();
    }

    /**
     * 날짜는 없지만 "12월에"처럼 지금과 다른 계절의 여행이면 축제를 붙이지 않는다.
     * 조회되는 축제는 오늘부터 60일 안의 것이라 그 여행 시기의 축제가 아니기 때문이다.
     */
    private boolean otherSeason(ConfirmedTravelConditions conditions) {
        String season = conditions.keywordNames("SEASON");
        return conditions.startDate() == null && !season.isEmpty()
            && !season.equals(ConditionReviewService.seasonName(festivals.today().getMonthValue()));
    }

    /**
     * 그 여행지 주소의 축제만 고른다. lookup으로 받은 시·도 전체 축제에서 주소에 시·군 이름(없으면 여행지 이름)이 들어 있는 것만 남긴다.
     * lookup은 홈 화면 탭 목록(findFestivals)이나 여행 기간 조회(findFestivalsBetween)다.
     */
    private List<RegionalFestival> inDestination(DestinationCandidate candidate,
                                                 Function<FestivalService.Province, List<RegionalFestival>> lookup) {
        String[] parts = candidate.region().strip().split("\\s+");
        String provinceName = Provinces.canonical(parts[0]);
        if (provinceName == null) return List.of();
        var province = FestivalService.PROVINCES.stream()
            .filter(p -> provinceName.equals(Provinces.canonical(p.regionName()))).findFirst().orElse(null);
        if (province == null) return List.of();
        String local = parts.length > 1 ? parts[1] : candidate.name();
        return lookup.apply(province).stream().filter(f -> f.address().contains(local)).toList();
    }

    /** 여행 날짜가 정해졌으면 그 기간과 겹치는 축제만 쓴다. 미정이면 진행 중이거나 곧 열리는 축제를 모두 쓴다. */
    static boolean duringTrip(RegionalFestival festival, LocalDate start, LocalDate end) {
        if (start == null || end == null) return true;
        return !festival.startDate().isAfter(end) && !festival.endDate().isBefore(start);
    }
}
