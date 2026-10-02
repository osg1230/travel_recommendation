package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.dto.ItineraryPlan;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.dto.RankedDestination;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정에 들어간 관광지의 관광공사 대표 사진을 찾아 두고 화면에 넘긴다.
 * 이름이 같고(관광공사 제목의 괄호 설명과 앞의 지역명은 빼고 비교) 위치가 맞는 곳이 딱 한 곳일 때만 연결해 다른 장소 사진을 붙이지 않는다.
 * 출처 표시만 하면 쓸 수 있는 사진(저작권 유형 Type1·Type3)만 쓴다.
 */
@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlacePhotoService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlacePhotoService.class);
    private static final Set<String> USABLE_COPYRIGHT = Set.of("Type1", "Type3");
    private final TourApiClient tour;
    private final PlaceRepository places;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;

    /** 일정의 관광지마다 사진을 찾아 저장한다. until이 지나면 멈추고, 실패해도 일정 초안에는 영향이 없다. */
    public void findForItinerary(ItineraryPlan.Input input, ItineraryPlan.Result result, Instant until) {
        if (!tour.configured()) return;
        Map<Long, PlaceCandidate> candidates = input.attractionsWithReserve().stream()
            .collect(Collectors.toMap(PlaceCandidate::placeId, Function.identity(), (a, b) -> a));
        String city = input.places().destination().name();
        var visited = new LinkedHashSet<Long>();
        result.days().forEach(day -> day.items().forEach(item -> {
            if ("VISIT".equals(item.type()) && item.placeId() != null) visited.add(item.placeId());
        }));
        for (Long id : visited) {
            if (Instant.now().isAfter(until)) return;
            var candidate = candidates.get(id);
            var place = places.findById(id).orElse(null);
            if (candidate == null || place == null || PlacePhotoDetails.read(place, Instant.now(), mapper) != null) continue;
            try {
                var matches = tour.searchPhotos(candidate.name()).stream().filter(found -> samePlace(candidate, city, found)).toList();
                var photo = matches.size() == 1 ? usable(matches.getFirst()) : null;
                save(id, photo == null ? new PlacePhotoDetails.Photo(null, null, Instant.now(), null) : photo);
            } catch (RuntimeException error) {
                log.warn("장소 사진 찾기를 건너뜀 place={} ({})", id, error.toString());
            }
        }
    }

    /** 장소 ID별로 저장해 둔 사진(찾은 것만). 초안과 저장한 일정 화면에서 쓴다. */
    @Transactional(readOnly = true, timeout = 10)
    public Map<Long, PlacePhotoDetails.Photo> photos(Collection<Long> placeIds) {
        var now = Instant.now();
        var result = new HashMap<Long, PlacePhotoDetails.Photo>();
        for (var place : places.findAllById(placeIds)) {
            var photo = PlacePhotoDetails.read(place, now, mapper);
            if (photo != null && photo.found()) result.put(place.getId(), photo);
        }
        return result;
    }

    /**
     * 추천 결과의 여행지(도시)마다 대표 사진을 찾아 저장한다. GPT가 추천 이유에서 든 중심 장소(centerPlaces, 실제 후보와 이름이 맞는 곳만)를
     * 관광공사에서 찾아 그 지역 안의 같은 이름 관광지 사진을 쓰고, 어느 관광지 사진인지(title)를 함께 저장해 도시 전체 사진으로 오해하지 않게 한다.
     * 중심 장소가 없는 여행지는 검색하지 않고 사진 없이 둔다. until이 지나면 멈추고, 실패해도 추천 결과는 그대로다.
     */
    public void findForDestinations(List<RankedDestination> ranked, Instant until) {
        if (!tour.configured()) return;
        for (var item : ranked) {
            if (Instant.now().isAfter(until)) return;
            var destination = item.destination();
            var place = places.findById(destination.placeId()).orElse(null);
            if (item.centerPlaces().isEmpty() || place == null || PlacePhotoDetails.read(place, Instant.now(), mapper) != null) continue;
            try {
                PlacePhotoDetails.Photo photo = null;
                for (String landmark : item.centerPlaces()) {
                    photo = tour.searchPhotos(landmark).stream()
                        .filter(found -> inRegion(destination, found) && sameName(landmark, destination.name(), found))
                        .map(PlacePhotoService::usable).filter(Objects::nonNull).findFirst().orElse(null);
                    if (photo != null) break;
                }
                save(destination.placeId(), photo == null ? new PlacePhotoDetails.Photo(null, null, Instant.now(), null) : photo);
            } catch (RuntimeException error) {
                log.warn("여행지 사진 찾기를 건너뜀 place={} ({})", destination.placeId(), error.toString());
            }
        }
    }

    /**
     * 관광공사 주소가 그 여행지 안인지. 여행지 지역명이 "강원특별자치도 강릉시"처럼 시·군까지 있으면 주소의 둘째 단어(시·군·구)가 같아야 한다.
     * "여수"라는 글자만 찾으면 하남시 여수동 같은 다른 지역이 섞이므로 단어 단위로 비교한다.
     * 시·도 하나가 여행지인 곳(서울특별시·제주특별자치도 등)은 주소의 시·도가 여행지 이름(서울·제주)으로 시작하면 된다.
     * 관광공사 주소의 시·도 표기는 옛 이름(전라남도 등)일 수 있어 시·도 이름 자체는 비교하지 않는다.
     */
    static boolean inRegion(DestinationCandidate destination, TourApiClient.SearchPlace found) {
        String[] region = (destination.region() == null ? "" : destination.region().strip()).split("\\s+");
        String[] address = (found.address() == null ? "" : found.address().strip()).split("\\s+");
        if (region[0].isEmpty() || address[0].isEmpty()) return false;
        if (region.length == 1) return address[0].startsWith(cityToken(destination));
        return address.length > 1 && address[1].equals(region[region.length - 1]);
    }
    /** 주소 비교에 쓰는 여행지 이름. 시·군 단위 여행지는 "강릉시" → 강릉처럼 시·군·구를 뺀 이름이고, 시·도 단위는 여행지 이름 그대로다. */
    static String cityToken(DestinationCandidate destination) {
        String[] parts = (destination.region() == null ? "" : destination.region().strip()).split("\\s+");
        String name = parts.length > 1 ? parts[parts.length - 1].replaceAll("(시|군|구)$", "") : destination.name();
        return PlaceOperatingService.compact(name == null ? "" : name);
    }

    /**
     * 같은 장소인지: 이름이 같고, 둘 다 좌표가 있으면 300m 안, 좌표가 없으면 도로명 주소가 같아야 한다.
     * 관광공사 제목은 "경주 불국사 [유네스코 세계유산]"처럼 앞에 지역명, 뒤에 괄호 설명이 붙기도 한다.
     */
    static boolean samePlace(PlaceCandidate place, String city, TourApiClient.SearchPlace found) {
        if (!sameName(place.name(), city, found)) return false;
        boolean located = place.latitude() != null && place.longitude() != null
            && Double.isFinite(found.latitude()) && Double.isFinite(found.longitude());
        if (located) {
            double dx = (place.longitude() - found.longitude()) * Math.cos(Math.toRadians(place.latitude())) * 111000;
            double dy = (place.latitude() - found.latitude()) * 111000;
            return Math.hypot(dx, dy) <= 300;
        }
        String road = PlaceOperatingService.road(place.address());
        return !road.isEmpty() && road.equals(PlaceOperatingService.road(found.address()));
    }

    /** 관광공사 제목(앞의 지역명과 뒤의 괄호 설명은 뺌)이 장소 이름과 같은지. 띄어쓰기·가운뎃점 차이는 무시한다. */
    static boolean sameName(String name, String city, TourApiClient.SearchPlace found) {
        String wanted = PlaceOperatingService.compact(name == null ? "" : name);
        String title = PlaceOperatingService.compact(found.name().replaceAll("\\s*[\\[(（][^\\])）]*[\\])）]", ""));
        String cityName = PlaceOperatingService.compact(city == null ? "" : city);
        return !wanted.isEmpty() && (title.equals(wanted) || title.equals(cityName + wanted));
    }

    // 사진 주소는 관광공사 이미지 서버의 http(s) 주소만 받고, 화면이 https에서도 열리도록 https로 바꾼다.
    static PlacePhotoDetails.Photo usable(TourApiClient.SearchPlace found) {
        String url = found.image() == null ? "" : found.image().strip();
        if (!USABLE_COPYRIGHT.contains(found.copyright()) || !url.matches("https?://tong\\.visitkorea\\.or\\.kr/[^\\s\"'<>]+")) return null;
        return new PlacePhotoDetails.Photo(url.replaceFirst("^http://", "https://"), found.copyright(), Instant.now(), found.name());
    }

    private void save(long placeId, PlacePhotoDetails.Photo photo) {
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> places.lockById(placeId).ifPresent(place -> {
            String json = PlacePhotoDetails.write(place, photo, mapper);
            if (json != null) { place.updateDetails(json); places.flush(); }
        }));
    }
}
