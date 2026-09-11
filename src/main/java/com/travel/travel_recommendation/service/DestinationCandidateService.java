package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class DestinationCandidateService {
    private final PlaceRepository places;
    private static final Map<String,List<String>> REGIONS=Map.of(
        "강릉",List.of("강릉","강릉시","강원","강원도","강원특별자치도","강원강릉","강원도강릉","강원도강릉시","강원특별자치도강릉시","강원특별자치도강릉"),
        "부산",List.of("부산","부산시","부산광역시"),
        "경주",List.of("경주","경주시","경북","경상북도","경북경주","경북경주시","경상북도경주","경상북도경주시"));

    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region) {
        String query=region==null?"":region.replaceAll("\\s+","");
        // 지역을 억지로 넓히거나 부분 문자열로 추측하지 않는다. 지원하지 않는 상세 지역은 보완을 요청한다.
        return places.findDestinations().stream()
            .filter(p -> REGIONS.containsKey(p.getName()))
            .filter(p -> query.isEmpty() || REGIONS.get(p.getName()).contains(query))
            .filter(p -> safeSource(p.getSourceUrl()) && p.getVerifiedAt()!=null)
            .map(p -> new DestinationCandidate(p.getId(),p.getName(),p.getRegion(),p.getDescription(),p.getSourceUrl(),p.getVerifiedAt()))
            .toList();
    }
    private boolean safeSource(String url) {
        try {
            URI uri=URI.create(url);
            return "https".equals(uri.getScheme()) && uri.getUserInfo()==null
                && Set.of("visitgangneung.net","www.visitbusan.net","gyeongju.go.kr").contains(uri.getHost());
        } catch (RuntimeException e) { return false; }
    }
}
