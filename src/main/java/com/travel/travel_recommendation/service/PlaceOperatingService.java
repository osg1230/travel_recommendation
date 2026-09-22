package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import java.util.regex.Pattern;
import java.time.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceOperatingService {
    private final PlaceCandidateService candidates;
    private final PlaceRepository places;
    private final TourApiClient tour;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;
    private static final Pattern ROAD = Pattern.compile("^(.+?(?:로|길)\\s+\\d+(?:-\\d+)?)(?:\\s.*|\\(.*)?$");

    public boolean refresh(UUID jobId, long destinationId, long placeId, String login, byte[] hash) {
        var before = candidates.find(jobId, destinationId, login, hash);
        var selected = select(before, placeId);
        if (OperatingHoursValidator.fresh(selected.operatingInfo(), Instant.now())) return true;
        String type = before.attractions().contains(selected) ? "ATTRACTION" : before.restaurants().contains(selected) ? "RESTAURANT" : "ACCOMMODATION";
        // 한 장소당 검색 1회·소개정보 1회로 제한하고 DB 트랜잭션 밖에서 호출한다.
        var matches = tour.search(selected.name()).stream().filter(p -> matches(selected, type, before.destination().name(), p)).toList();
        if (matches.size() != 1) return false;
        var match = matches.getFirst();
        var info = tour.findOperatingInfo(match.id(), match.type()).orElse(null);
        if (info == null) return false;
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        return transaction.execute(status -> {
            places.lockDestination(destinationId).orElseThrow(PlaceOperatingService::changed);
            var current = candidates.find(jobId, destinationId, login, hash);
            if (!before.destination().equals(current.destination()) || !selected.equals(select(current, placeId))) throw changed();
            var place = places.lockById(placeId).orElseThrow(PlaceOperatingService::changed);
            place.updateDetails(PlaceOperatingDetails.write(place, info, mapper));
            places.flush(); return true;
        });
    }

    private static PlaceCandidate select(PlaceCandidateView view, long id) {
        var pool = new ArrayList<>(view.attractions()); pool.addAll(view.restaurants()); pool.addAll(view.accommodations());
        return pool.stream().filter(p -> p.placeId() == id).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "이 추천에서 조회할 수 있는 장소가 아니에요."));
    }

    static boolean matches(PlaceCandidate place, String type, String city, TourApiClient.SearchPlace found) {
        var types = switch (type) {
            case "ATTRACTION" -> Set.of("12", "14", "28");
            case "RESTAURANT" -> Set.of("39");
            default -> Set.of("32");
        };
        String name = compact(place.name()), candidateName = compact(found.name());
        boolean sameName = name.equals(candidateName) || (compact(city) + name).equals(candidateName) || name.equals(compact(city) + candidateName);
        String address = road(place.address()), other = road(found.address());
        if (!types.contains(found.type()) || !sameName || address.isEmpty() || !address.equals(other)) return false;
        if (place.longitude() != null && place.latitude() != null) {
            double dx = (place.longitude() - found.longitude()) * Math.cos(Math.toRadians(place.latitude())) * 111000;
            double dy = (place.latitude() - found.latitude()) * 111000;
            if (Math.hypot(dx, dy) > 200) return false;
        }
        return true;
    }
    private static String road(String address) {
        if (address == null) return "";
        var match = ROAD.matcher(KakaoMapClient.normalize(address));
        return match.matches() ? match.group(1) : "";
    }
    private static String compact(String text) { return text.replaceAll("[\\s·ㆍ・]", ""); }
    private static RecommendationException changed() { return new RecommendationException("PLACES_CHANGED", "장소 정보가 변경됐어요. 목록을 다시 열어 주세요."); }
}
