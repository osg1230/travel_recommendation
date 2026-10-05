package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.dto.RankedDestination;
import com.travel.travel_recommendation.dto.RegionalFestival;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.AiJobRepository;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 추천 결과에 붙은 축제가 열리는 곳을 카카오 장소로 확인해 그 여행지의 관광지로 저장한다. 일정에 축제를 넣으려면 실제 장소가 필요하기 때문이다.
 * 관광공사의 행사 장소 설명(예: "보은읍 뱃들공원 일원")에서 장소 이름을 뽑아, 행사 좌표 1km 안에서 이름이 맞는 카카오 장소를 찾는다.
 * 이름으로 못 찾으면 행사 좌표 500m 안의 가장 가까운 관광명소를 쓴다. 그래서 일정에는 "축제가 이 근처에서 열려요"라고 안내한다.
 * 찾지 못한 축제는 추천 카드에만 보이고 일정에는 넣지 않는다. 외부 호출 동안 DB 연결을 잡지 않도록 검색과 저장을 나눈다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class FestivalPlaceService {
    /** 여행지 한 곳에서 장소를 찾아 볼 축제 수와, 축제 하나에 쓰는 검색어 수. 카카오 호출이 늘지 않게 한다. */
    static final int MAX_FESTIVALS = 2, MAX_QUERIES = 2;
    /** 장소 이름으로 못 찾았을 때 행사 좌표 주변에서 관광명소를 찾는 반경. 걸어서 갈 수 있는 거리로 둔다. */
    static final int NEARBY_METERS = 500;
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final KakaoMapClient maps;
    private final ObjectMapper mapper;
    private final ObjectProvider<FestivalPlaceService> self;

    /** 추천이 끝난 뒤 한 번 부른다. 실패해도 추천 결과는 그대로다. */
    public void attach(UUID jobId, List<RankedDestination> ranked) {
        Map<String, KakaoPlace> venues = new LinkedHashMap<>();
        for (var destination : ranked)
            for (var festival : destination.festivals().stream().limit(MAX_FESTIVALS).toList())
                venue(festival, destination.destination().region()).ifPresent(place -> venues.put(festival.id(), place));
        if (!venues.isEmpty()) self.getObject().save(jobId, venues);
    }

    /** 축제가 열리는 카카오 장소. 좌표나 장소 이름이 없거나 맞는 곳이 없으면 비어 있다. */
    Optional<KakaoPlace> venue(RegionalFestival festival, String region) {
        if (festival.longitude() == null || festival.latitude() == null) return Optional.empty();
        try {
            for (String query : venueNames(festival.place())) {
                var found = maps.findNear(query, festival.longitude(), festival.latitude(), region);
                if (found.isPresent()) return found;
            }
            // 장소 설명이 비었거나 번지뿐인 축제가 많다. 이름으로 못 찾으면 행사 좌표에서 가장 가까운 관광명소를 쓴다.
            return maps.nearestAttraction(festival.longitude(), festival.latitude(), region, NEARBY_METERS);
        } catch (RecommendationException e) {
            return Optional.empty();
        }
    }

    /**
     * 행사 장소 설명에서 장소 이름으로 보이는 단어를 뽑는다. 읍·면·동 같은 행정구역과 '일원'·'일대' 같은 말은 뺀다.
     * 예) "보은읍 뱃들공원 일원" → [뱃들공원], "경포해변 및 경포호수광장" → [경포해변, 경포호수광장]
     */
    static List<String> venueNames(String place) {
        if (place == null) return List.of();
        List<String> names = new ArrayList<>();
        for (String word : place.split("[\\s,()·/]+")) {
            String name = word.replaceAll("(일원|일대|주변|인근|특설무대)$", "");
            // '○○거리'는 '리'로 끝나지만 행정구역이 아니라 장소 이름이다.
            boolean district = name.matches(".*(특별시|광역시|도|시|군|구|읍|면|동|리|로|길)") && !name.endsWith("거리");
            if (name.length() < 3 || district || name.matches(".*[0-9].*")) continue;
            if (!names.contains(name)) names.add(name);
            if (names.size() == MAX_QUERIES) break;
        }
        return names;
    }

    /** 찾은 장소를 관광지로 저장하고 추천 결과에 축제 ID → 관광지 ID를 적는다. 그 사이 추천이 바뀌었으면 저장하지 않는다. */
    @Transactional(timeout = 10)
    public void save(UUID jobId, Map<String, KakaoPlace> venues) {
        var job = jobs.lockById(jobId).orElse(null);
        if (job == null || !"RECOMMENDATION".equals(job.getJobType()) || !"COMPLETED".equals(job.getStatus())) return;
        List<RankedDestination> updated = new ArrayList<>();
        for (var ranked : mapper.readValue(job.getResultJson(), RankedDestination[].class)) {
            Map<String, Long> placeIds = new LinkedHashMap<>();
            for (var festival : ranked.festivals()) {
                var data = venues.get(festival.id());
                if (data == null || !data.region().equals(ranked.destination().region())) continue;
                var existing = places.findBySourceProviderAndSourcePlaceId("KAKAO", data.sourceId());
                Place place;
                if (existing.isPresent()) {
                    place = existing.get();
                    // 이미 다른 지역·유형으로 저장된 곳은 바꾸지 않고 축제 장소로도 쓰지 않는다.
                    if (!place.isActive() || !place.getRegion().equals(data.region()) || !"ATTRACTION".equals(place.getPlaceType())) continue;
                    if (data.checkedAt().isAfter(place.getVerifiedAt())) place.refreshKakao(data);
                } else place = places.save(Place.fromKakao(data));
                placeIds.put(festival.id(), place.getId());
            }
            updated.add(ranked.withFestivalPlaces(placeIds));
        }
        places.flush();
        job.attachToRecommendation(mapper.writeValueAsString(updated));
        jobs.flush();
    }
}
