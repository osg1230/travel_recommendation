package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.repository.PlaceRepository;
import com.travel.travel_recommendation.service.BudgetGuide.StayTier;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 추천 여행지마다 확인된 숙소를 유형별 예산대로 묶는다(SFR-03-03). 금액은 만들지 않는다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class BudgetGuideService {
    /** 예산대 하나에 보여 줄 숙소 이름 수. */
    static final int NAMES_PER_TIER=3;
    private final PlaceRepository places;

    public record TierGroup(StayTier tier, int count, List<String> examples) {}
    public record DestinationTiers(String destination, List<TierGroup> tiers) {}

    /** region은 여행지의 지역(예: 강원특별자치도 강릉시)이다. 활성 숙소만 세며 유형을 판단할 수 없는 곳은 뺀다. */
    @Transactional(readOnly=true,timeout=10)
    public DestinationTiers tiers(String destination,String region) {
        Map<StayTier,List<String>> grouped=new EnumMap<>(StayTier.class);
        var cacheLimit=java.time.LocalDateTime.now(java.time.ZoneOffset.UTC).minusHours(24);
        for(var place:places.findLocalPlaces(region)) {
            if(!"ACCOMMODATION".equals(place.getPlaceType())) continue;
            // 장소 목록과 같이 카카오 후보는 조회 후 24시간 안의 것만 쓴다.
            if("KAKAO".equals(place.getSourceProvider()) && (place.getVerifiedAt()==null || !place.getVerifiedAt().isAfter(cacheLimit))) continue;
            StayTier.of(place.getSubtype(),place.getName()).ifPresent(tier->grouped.computeIfAbsent(tier,k->new ArrayList<>()).add(place.getName()));
        }
        List<TierGroup> tiers=new ArrayList<>();
        for(var tier:StayTier.values()) {
            var names=grouped.getOrDefault(tier,List.of());
            tiers.add(new TierGroup(tier,names.size(),names.stream().limit(NAMES_PER_TIER).toList()));
        }
        return new DestinationTiers(destination,tiers);
    }
}
