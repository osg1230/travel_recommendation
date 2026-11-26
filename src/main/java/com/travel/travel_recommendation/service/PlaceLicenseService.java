package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정 후보로 뽑힌 음식점·숙소가 폐업했는지 행정안전부 인허가 자료로 확인해 장소에 남긴다(2026-11-26).
 * 폐업으로 확인한 곳은 PlaceCandidateService.usable이 후보에서 뺀다. 관광지는 인허가 자료가 없어 확인하지 않는다.
 */
@Service
@Profile({"sqlserver", "sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceLicenseService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PlaceLicenseService.class);
    /** 한 번에 쓰는 시간. 인허가 조회가 느린 날에도 장소 목록이 이 시간 넘게 늦어지지 않게 한다. */
    static final Duration BUDGET = Duration.ofSeconds(8);
    private final PlaceCandidateService candidates;
    private final PlaceRepository places;
    private final LicenseClient licenses;
    private final ObjectMapper mapper;
    private final PlatformTransactionManager transactions;

    /**
     * 후보 음식점·숙소 중 최근 확인하지 않은 곳의 영업 상태를 조회해 저장하고, 폐업으로 확인한 곳의 수를 돌려준다.
     * 폐업한 곳이 빠지면 다음 순서의 장소가 후보로 올라오므로 한 번 더 확인한다. 조회가 실패하거나 시간이 다 되면 남은 곳은 그대로 둔다.
     */
    public int excludeClosed(UUID jobId, long destinationId, String login, byte[] hash) {
        if (!licenses.configured()) return 0;
        Instant deadline = Instant.now().plus(BUDGET);
        int closedCount = 0;
        for (int pass = 0; pass < 2; pass++) {
            var view = candidates.find(jobId, destinationId, login, hash);
            int closedInPass = 0;
            try {
                closedInPass += check(view.restaurants(), LicenseClient.RESTAURANT_SERVICES, deadline);
                closedInPass += check(view.accommodations(), LicenseClient.STAY_SERVICES, deadline);
            } catch (RecommendationException error) {
                // 인허가 서버는 느리거나 실패하는 때가 잦다. 그럴 때는 남은 곳을 조회하지 않고 후보를 그대로 둔다.
                log.warn("영업 상태 확인을 중단했어요 ({})", error.getCode());
                return closedCount;
            }
            closedCount += closedInPass;
            if (closedInPass == 0) break;
        }
        return closedCount;
    }

    private int check(List<PlaceCandidate> list, List<String> services, Instant deadline) {
        int closedCount = 0;
        for (var candidate : list) {
            if (Instant.now().isAfter(deadline)) break;
            var place = places.findById(candidate.placeId()).orElse(null);
            if (place == null || PlaceLicenseDetails.checkedRecently(place, mapper, Instant.now())) continue;
            var code = LicenseClient.regionCode(candidate.address());
            String keyword = keyword(candidate.name());
            if (code.isEmpty() || keyword.isEmpty()) continue;
            // 업종이 바뀌어 다른 자료에 영업 중으로 있을 수 있으므로 두 자료를 모두 본 뒤에 판단한다.
            List<LicenseClient.Business> matched = new ArrayList<>();
            for (String service : services)
                for (var business : licenses.search(service, code.get(), keyword))
                    if (sameBusiness(candidate.name(), candidate.address(), business)) matched.add(business);
            boolean closed = closedDown(matched);
            String closedOn = matched.stream().map(LicenseClient.Business::closedOn).max(Comparator.naturalOrder()).orElse("");
            save(candidate, closed, closed ? closedOn : "");
            if (closed) { closedCount++; log.info("폐업으로 확인해 후보에서 뺐어요 placeId={} closedOn={}", candidate.placeId(), closedOn); }
        }
        return closedCount;
    }

    private void save(PlaceCandidate candidate, boolean closed, String closedOn) {
        var transaction = new TransactionTemplate(transactions); transaction.setTimeout(10);
        transaction.executeWithoutResult(status -> {
            // details_json을 함께 쓰는 운영정보·가격 저장과 겹치지 않게 잠그고, 조회하는 사이 이름·주소가 바뀌었으면 남기지 않는다.
            var place = places.lockById(candidate.placeId()).orElse(null);
            if (place == null || !candidate.name().equals(place.getName()) || !candidate.address().equals(place.getAddress())) return;
            place.updateDetails(PlaceLicenseDetails.write(place, closed, closedOn, Instant.now(), mapper));
        });
    }

    /** 인허가 자료에서 찾을 낱말. 카카오 상호의 첫 단어를 쓴다(예: "동화가든 본점" → "동화가든"). 한 글자면 상호 전체를 붙여 쓴다. */
    static String keyword(String name) {
        if (name == null || name.isBlank()) return "";
        String first = name.strip().split("\\s+")[0];
        String word = first.length() >= 2 ? first : PlaceOperatingService.compact(name);
        return word.length() >= 2 && word.length() <= 100 ? word : "";
    }

    /**
     * 같은 사업장인지: 도로명과 건물번호가 같고, 띄어쓰기를 뺀 이름 한쪽이 다른 쪽에 들어 있어야 한다.
     * 예) 카카오 "테라로사 서울역점"과 인허가 "(주)학산 테라로사 서울역점". 인허가 주소의 쉼표 뒤(층·호)는 보지 않는다.
     */
    static boolean sameBusiness(String placeName, String placeAddress, LicenseClient.Business business) {
        String road = PlaceOperatingService.road(placeAddress);
        if (road.isEmpty() || !road.equals(PlaceOperatingService.road(business.roadAddress().replaceFirst(",.*$", "")))) return false;
        String name = PlaceOperatingService.compact(placeName), other = PlaceOperatingService.compact(business.name());
        return name.length() >= 2 && other.length() >= 2 && (name.contains(other) || other.contains(name));
    }

    /** 같은 사업장의 기록 중 영업 중인 것이 없고 폐업 기록이 있을 때만 폐업으로 본다. 주인이 바뀌어 새로 허가받은 가게는 영업 기록이 따로 있다. */
    static boolean closedDown(List<LicenseClient.Business> matched) {
        return matched.stream().noneMatch(b -> LicenseClient.OPEN.equals(b.statusCode()))
            && matched.stream().anyMatch(b -> LicenseClient.CLOSED.equals(b.statusCode()));
    }
}
