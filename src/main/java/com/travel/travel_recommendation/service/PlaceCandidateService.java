package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.AiJobRepository;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceCandidateService {
    private final RecommendationJobService jobs;
    private final AiJobRepository jobRepository;
    private final PlaceRepository places;
    private final ObjectMapper mapper;

    private static final Set<String> SOURCE_HOSTS = Set.of(
        "www.gn.go.kr", "english.visitkorea.or.kr", "www.visitbusan.net",
        "www.gyeongju.go.kr", "gyeongju.go.kr", "search.gyeongju.go.kr",
        "www.seamarqhotel.com", "booking.seamarqhotel.com", "www.shillahotels.com",
        "www.haeundaegalbi.com", "www.lahanhotels.com", "lahanhotels.com");

    @Transactional(readOnly = true, timeout = 10)
    public PlaceCandidateView find(UUID jobId, long destinationId, String loginId, byte[] guestHash) {
        // URL의 지역 ID만 믿지 않고 작업 소유권·만료와 실제 추천 목록을 먼저 확인한다.
        var job = jobs.view(jobId, loginId, guestHash);
        if (!"COMPLETED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "여행지 추천을 마친 뒤 지역을 선택해 주세요.");
        }
        var destination = job.recommendations().stream().map(RankedDestination::destination)
            .filter(candidate -> candidate.placeId() == destinationId).findFirst()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "이 추천 결과에서 선택할 수 없는 지역이에요. 추천 결과로 돌아가 다시 선택해 주세요."));
        var current = places.findById(destinationId).filter(Place::isActive)
            .filter(place -> "DESTINATION".equals(place.getPlaceType()))
            .filter(place -> destination.name().equals(place.getName()) && destination.region().equals(place.getRegion()))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "선택한 지역 정보가 변경됐어요. 여행 조건에서 후보를 다시 조회해 주세요."));
        var storedJob = jobRepository.findById(jobId).orElseThrow(() ->
            new ResponseStatusException(HttpStatus.NOT_FOUND, "요청을 찾을 수 없거나 만료됐어요."));
        var conditions = mapper.readValue(storedJob.getRequestJson(), ConfirmedTravelConditions.class);
        boolean needsAccommodation = !Boolean.FALSE.equals(conditions.accommodationNeeded())
            && conditions.durationDays() != null && conditions.durationDays() > 1;
        // 부분 문자열 검색으로 다른 시·군을 섞지 않는다. 출처·주소를 확인한 활성 장소만 반환한다.
        var candidates = places.findLocalPlaces(current.getRegion()).stream()
            .filter(place -> usable(place, current.getRegion())).toList();
        return new PlaceCandidateView(jobId, destination, conditions.durationDays(), needsAccommodation,
            ofType(candidates, "ATTRACTION"), needsAccommodation ? ofType(candidates, "ACCOMMODATION") : List.of(),
            ofType(candidates, "RESTAURANT"));
    }

    private boolean usable(Place place, String region) {
        return place.isActive() && region.equals(place.getRegion())
            && place.getName() != null && !place.getName().isBlank()
            && place.getAddress() != null && !place.getAddress().isBlank()
            && place.getVerifiedAt() != null && !place.getVerifiedAt().isAfter(LocalDateTime.now(ZoneOffset.UTC))
            && safeSource(place.getSourceUrl());
    }

    private boolean safeSource(String url) {
        try {
            var uri = URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getUserInfo() == null
                && (uri.getPort() == -1 || uri.getPort() == 443) && SOURCE_HOSTS.contains(uri.getHost());
        } catch (RuntimeException error) {
            return false;
        }
    }

    private List<PlaceCandidate> ofType(List<Place> candidates, String type) {
        return candidates.stream().filter(place -> type.equals(place.getPlaceType())).map(place -> {
            // 주소 검색 링크이며 확정 좌표나 길찾기 결과로 표시하지 않는다.
            String query = URLEncoder.encode(place.getAddress() + " " + place.getName(), StandardCharsets.UTF_8)
                .replace("+", "%20");
            return new PlaceCandidate(place.getId(), place.getName(), place.getAddress(), place.getSubtype(),
                place.getDescription(), place.getSourceUrl(), "https://map.kakao.com/link/search/" + query,
                place.getVerifiedAt());
        }).toList();
    }
}
