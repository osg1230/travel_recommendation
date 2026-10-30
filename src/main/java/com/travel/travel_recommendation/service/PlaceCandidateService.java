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

    /** 이 추천에서 찾은 여행 목적 시설 중 이 여행지에 있는 관광지. 장소 목록 표시와 일정의 필수 방문 기본값에 쓴다. label은 목적을 부르는 말(여행 목적 또는 AI가 제안한 여행 방향)이다. */
    public record PurposeView(String purpose, List<Long> placeIds, String label) {
        public PurposeView(String purpose, List<Long> placeIds) { this(purpose, placeIds, "여행 목적"); }
        public static final PurposeView NONE = new PurposeView("", List.of());
    }
    @Transactional(readOnly = true, timeout = 10)
    public PurposeView purpose(UUID jobId, long destinationId, String loginId, byte[] guestHash) {
        jobs.view(jobId, loginId, guestHash);
        var stored = jobRepository.findById(jobId).orElse(null);
        if (stored == null) return PurposeView.NONE;
        var c = mapper.readValue(stored.getRequestJson(), ConfirmedTravelConditions.class);
        if (c.purposeSearch() == null) return PurposeView.NONE;
        return new PurposeView(c.travelPurpose() == null ? "" : c.travelPurpose(),
            c.purposeSearch().placesIn(destinationId).stream().map(p -> p.placeId()).toList(), c.purposeLabel());
    }

    @Transactional(readOnly = true, timeout = 10)
    public PlaceCandidateView find(UUID jobId, long destinationId, String loginId, byte[] guestHash) {
        return findForSchedule(jobId, destinationId, loginId, guestHash, null);
    }

    // 일정 화면에서 보완한 기간·숙박 조건을 적용한다. 추천 원본은 변경하지 않는다.
    @Transactional(readOnly = true, timeout = 10)
    public PlaceCandidateView findForSchedule(UUID jobId, long destinationId, String loginId, byte[] guestHash,
            ConfirmedTravelConditions scheduleConditions) {
        // URL의 지역 ID만 믿지 않고 작업 소유권·만료와 실제 추천 목록을 먼저 확인한다.
        var job = jobs.view(jobId, loginId, guestHash);
        if (!"COMPLETED".equals(job.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "여행지 추천을 마친 뒤 지역을 선택해 주세요.");
        }
        // 축제가 열리는 곳으로 확인한 관광지. 목적 시설처럼 후보 수 제한에 밀리지 않게 앞에 둔다.
        var festivalIds = job.recommendations().stream().filter(r -> r.destination().placeId() == destinationId)
            .flatMap(r -> r.festivalPlaceIds().values().stream()).toList();
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
        var conditions = scheduleConditions == null ? mapper.readValue(storedJob.getRequestJson(), ConfirmedTravelConditions.class) : scheduleConditions;
        boolean needsAccommodation = !Boolean.FALSE.equals(conditions.accommodationNeeded())
            && conditions.durationDays() != null && conditions.durationDays() > 1;
        // 부분 문자열 검색으로 다른 시·군을 섞지 않는다. 출처·주소를 확인한 활성 장소만 반환한다.
        // 여행 목적 시설(예: 아쿠아리움)은 후보 수 제한에 밀리지 않도록 맨 앞에 둔다.
        var purposeIds = conditions.purposeSearch() == null ? List.<Long>of()
            : conditions.purposeSearch().placesIn(destinationId).stream().map(p -> p.placeId()).toList();
        // 고른 여행 취향(예: 문화·역사)에 맞는 관광지는 목적 시설 다음으로 둔다. 관광지 후보 수는 여행 일수로 정해져(TripConstraints.candidateLimit) 뒤쪽은 잘리기 때문이다.
        // 경주처럼 대표 분류가 문화·역사인 여행지는 취향을 고르지 않아도 문화유산이 잘리지 않게 한다.
        var themes = ThemePlaces.withHeritageDestination(conditions.themeNames(), current.getName(), current.getSubtype());
        var candidates = places.findLocalPlaces(current.getRegion()).stream()
            .filter(place -> usable(place, current.getRegion()))
            .sorted(Comparator.comparing((Place p)->!purposeIds.contains(p.getId()) && !festivalIds.contains(p.getId()))
                .thenComparing((Place p)->ThemePlaces.matched(p.getName(),p.getSubtype(),themes).isEmpty())
                .thenComparing((Place p)->!"KAKAO".equals(p.getSourceProvider()))
                // 카카오 검색 결과에 먼저 나온 곳부터 둔다. 이름순으로 자르면 뒤쪽 글자로 시작하는 곳(정동진 등)이 빠진다.
                .thenComparingInt((Place p)->PlaceRankDetails.read(p,mapper))
                .thenComparing(Place::getName).thenComparing(Place::getId)).toList();
        var distinct=new ArrayList<Place>(); Set<String> seen=new HashSet<>();
        for(var place:candidates) {
            // 같은 유형·건물 주소의 후보는 하나만 표시해 동일 장소의 다른 명칭을 중복 방문하지 않는다.
            String address=KakaoMapClient.normalize(place.getAddress()).replaceFirst("^(.+?(?:로|길)\\s+\\d+(?:-\\d+)?)(?:\\s+.*)?$","$1");
            String key=place.getPlaceType()+":"+address;
            if(seen.add(key)) distinct.add(place);
        }
        Set<Long> keep=new HashSet<>(purposeIds); keep.addAll(festivalIds);
        var spread=spreadKinds(distinct,keep,themes);
        return new PlaceCandidateView(jobId, destination, conditions.durationDays(), needsAccommodation,
            ofType(spread, "ATTRACTION", TripConstraints.candidateLimit(conditions.durationDays())),
            needsAccommodation ? ofType(spread, "ACCOMMODATION", 5) : List.of(), ofType(spread, "RESTAURANT", 8));
    }

    /** 관광지 후보 앞쪽에 둘 같은 종류의 수. 고른 여행 취향에 맞는 종류(예: 바다 취향의 해변)는 더 둔다. */
    static final int KIND_LIMIT=3, THEME_KIND_LIMIT=5;
    /** 종류로 세지 않는 넓은 분류. 카카오가 세부 분류 없이 "여행 > 관광,명소"로만 준 곳은 서로 다른 장소라 함께 묶지 않는다. */
    private static final Set<String> BROAD_KINDS=Set.of("","관광,명소");

    /**
     * 같은 종류의 관광지가 후보 앞쪽을 다 차지하지 않게, 종류마다 앞에서 KIND_LIMIT곳까지만 제자리에 두고 나머지는 관광지 후보 맨 뒤로 보낸다.
     * 인기 순서를 그대로 쓰면 강릉은 후보 16곳 중 9곳이 해변이 됐다(2026-10-30 측정). 뒤로 보낸 곳도 빼지는 않는다.
     * 목적 시설·축제 장소(keep)는 세지 않고 제자리에 둔다. 관광지가 아닌 숙소·음식점의 순서는 바꾸지 않는다.
     */
    private List<Place> spreadKinds(List<Place> ordered,Set<Long> keep,List<String> themes) {
        Map<String,Integer> counts=new HashMap<>();
        List<Place> front=new ArrayList<>(), back=new ArrayList<>();
        for(var place:ordered) {
            String kind=TripConstraints.kind(place.getSubtype());
            if(!"ATTRACTION".equals(place.getPlaceType()) || keep.contains(place.getId()) || BROAD_KINDS.contains(kind)) { front.add(place); continue; }
            int limit=ThemePlaces.matched(place.getName(),place.getSubtype(),themes).isEmpty()?KIND_LIMIT:THEME_KIND_LIMIT;
            if(counts.merge(kind,1,Integer::sum)<=limit) front.add(place); else back.add(place);
        }
        front.addAll(back);
        return front;
    }

    /**
     * 후보 목록(개수 제한) 밖이어도 지금 확인된 이 지역 관광지면 일정 후보 형식으로 돌려준다.
     * 수정안이 유지하는 관광지는 후보 순서가 바뀌어 상위 N곳에서 밀려났을 뿐이면 그대로 쓴다.
     */
    @Transactional(readOnly = true, timeout = 10)
    public Optional<PlaceCandidate> usableAttraction(long placeId, String region) {
        return places.findById(placeId).filter(place -> "ATTRACTION".equals(place.getPlaceType()))
            .filter(place -> usable(place, region)).map(this::candidate);
    }

    private boolean usable(Place place, String region) {
        return place.isActive() && region.equals(place.getRegion())
            && place.getName() != null && !place.getName().isBlank()
            && place.getAddress() != null && !place.getAddress().isBlank()
            && place.getVerifiedAt() != null && !place.getVerifiedAt().isAfter(KoreaTime.now())
            && ("KAKAO".equals(place.getSourceProvider()) ? usableKakao(place) : safeSource(place.getSourceUrl()));
    }

    private boolean usableKakao(Place place) {
        if(!place.getVerifiedAt().isAfter(KoreaTime.now().minusHours(24))
            || place.getLongitude()==null || place.getLatitude()==null
            || !place.getSourcePlaceId().matches("[1-9][0-9]{0,19}")
            || !("https://place.map.kakao.com/"+place.getSourcePlaceId()).equals(place.getSourceUrl())) return false;
        try { new KakaoMapClient.Location(place.getAddress(),place.getLongitude().doubleValue(),place.getLatitude().doubleValue(),place.getVerifiedAt().atZone(KoreaTime.ZONE).toInstant()); return true; }
        catch(IllegalArgumentException error) { return false; }
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

    private List<PlaceCandidate> ofType(List<Place> candidates, String type, int limit) {
        return candidates.stream().filter(place -> type.equals(place.getPlaceType())).limit(limit).map(this::candidate).toList();
    }

    /** 장소 한 곳을 일정 후보 형식으로 바꾼다. 저장한 일정에서 수정 초안을 다시 만들 때도 쓴다. */
    public PlaceCandidate candidate(Place place) {
        // 주소 검색 링크이며 확정 좌표나 길찾기 결과로 표시하지 않는다.
        String query = URLEncoder.encode(place.getAddress() + " " + place.getName(), StandardCharsets.UTF_8)
            .replace("+", "%20");
        return new PlaceCandidate(place.getId(), place.getName(), place.getAddress(), place.getSubtype(),
            place.getDescription(), place.getSourceUrl(), "KAKAO".equals(place.getSourceProvider())?place.getSourceUrl():"https://map.kakao.com/link/search/" + query,
            place.getVerifiedAt(),place.getLongitude()==null?null:place.getLongitude().doubleValue(),
            place.getLatitude()==null?null:place.getLatitude().doubleValue(),place.getSourceProvider(),PlaceOperatingDetails.read(place,mapper),
            PlaceRankDetails.popular(place,mapper));
    }
}
