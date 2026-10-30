package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.DestinationCandidate;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.net.URI;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 여행 조건의 지역으로 추천 후보 여행지를 고른다. 여행지는 DB의 DESTINATION 행(공식 관광 사이트로 확인한 전국 주요 도시)이다.
 * AI 추천은 한 번에 최대 5곳만 비교하므로, 후보가 더 많으면 여행 취향과 맞는 곳을 먼저 5곳 고른다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class DestinationCandidateService {
    /** OpenAiRecommendationClient가 한 번에 비교하는 후보 수의 상한. */
    static final int MAX_CANDIDATES=5;
    /** 시·군 공식 사이트(.go.kr) 외에 확인한 지자체·관광공사 공식 관광 사이트. */
    private static final Set<String> OFFICIAL_HOSTS=Set.of("visitgangneung.net","www.visitbusan.net","korean.visitseoul.net",
        "daejeontour.co.kr","www.visitjeju.net",
        // 한국관광공사 대한민국 구석구석. 추천 중에 관광공사 정보로 확인해 추가한 여행지(RegionSuggestionService)의 출처다.
        "korean.visitkorea.or.kr");
    private final PlaceRepository places;

    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region) {
        return find(region,List.of());
    }

    /**
     * region은 시·도 선택값("강원"), 여행지 이름("강릉"), 전체 지역명("강원특별자치도 강릉시") 중 하나이며 비어 있으면 전국이다.
     * themes는 사용자가 고른 여행 취향 이름(바다·맛집 등)으로, 후보가 5곳보다 많을 때만 순서를 정하는 데 쓴다.
     */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region,List<String> themes) {
        return find(region,themes,List.of());
    }

    /** purposeDestinationIds는 여행 목적 시설(예: 아쿠아리움)이 있는 여행지다. 후보가 5곳보다 많을 때 가장 먼저 고른다. */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region,List<String> themes,List<Long> purposeDestinationIds) {
        return find(region,themes,purposeDestinationIds,List.of());
    }

    /** 추천 조건 그대로 후보를 고른다. 후보 준비와 추천 저장 직전 재확인이 같은 기준을 쓰도록 이 메서드를 쓴다. */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(com.travel.travel_recommendation.dto.ConfirmedTravelConditions c) {
        var matched=matched(c.region(),c.excludedRegions());
        return pick(matched,c.themeNames(),blank(c.region()),c.suggestedDestinationIds(),c.purposeDestinationIds(),c.purposeThemes())
            .stream().map(DestinationCandidateService::candidate).toList();
    }

    /** purposeThemes는 목적과 가까운 여행지 분류(예: 갯벌 체험 → 바다)로, 사용자가 고른 취향 다음으로 순서를 정한다. */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region,List<String> themes,List<Long> purposeDestinationIds,List<String> purposeThemes) {
        return find(region,themes,purposeDestinationIds,purposeThemes,List.of());
    }

    /** excludedRegions는 사용자가 가지 않겠다고 한 지역 이름("부산", "강원" 등)이다. 지역 조건과 같은 방식으로 맞는 여행지를 후보에서 뺀다. */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> find(String region,List<String> themes,List<Long> purposeDestinationIds,List<String> purposeThemes,
                                           List<String> excludedRegions) {
        return pick(matched(region,excludedRegions),themes,blank(region),purposeDestinationIds,purposeThemes)
            .stream().map(DestinationCandidateService::candidate).toList();
    }

    // 지역을 억지로 넓히거나 부분 문자열로 추측하지 않는다. 지원하지 않는 상세 지역은 보완을 요청한다.
    private List<Place> matched(String region,List<String> excludedRegions) {
        String query=region==null?"":region.replaceAll("\\s+","");
        return usablePlaces().stream().filter(p -> query.isEmpty() || matches(p,query))
            .filter(p -> excludedRegions.stream().noneMatch(name -> matches(p,name.replaceAll("\\s+","")))).toList();
    }
    private static boolean blank(String region) { return region==null || region.isBlank(); }

    /** 출처를 확인한 전체 여행지(5곳으로 줄이기 전). 목적 시설이 어느 여행지에 있는지 나눌 때 쓴다. */
    @Transactional(readOnly=true, timeout=10)
    public List<DestinationCandidate> usable() {
        return usablePlaces().stream().map(DestinationCandidateService::candidate).toList();
    }

    private List<Place> usablePlaces() {
        return places.findDestinations().stream()
            .filter(p -> safeSource(p.getSourceUrl()) && p.getVerifiedAt()!=null && p.getRegion()!=null).toList();
    }
    private static DestinationCandidate candidate(Place p) {
        return new DestinationCandidate(p.getId(),p.getName(),p.getRegion(),p.getDescription(),p.getSourceUrl(),p.getVerifiedAt());
    }

    static List<Place> pick(List<Place> matched,List<String> themes,boolean nationwide) {
        return pick(matched,themes,nationwide,List.of(),List.of());
    }

    /**
     * 후보가 5곳 이하면 그대로 쓴다. 더 많으면 여행 목적 시설이 있는 곳, 여행 취향과 겹치는 분류가 많은 곳,
     * 목적과 가까운 분류(예: 갯벌 체험 → 바다)가 겹치는 곳 순서로 고르고,
     * 같으면 먼저 등록한 곳을 앞에 둔다. 전국에서 고를 때는 한 시·도에 몰리지 않도록 시·도마다 한 곳씩 먼저 고른 뒤 남는 자리를 채운다.
     * 같은 조건이면 늘 같은 후보가 나와야 추천 저장 직전의 재확인(currentPlacesMatch)과 맞는다.
     */
    static List<Place> pick(List<Place> matched,List<String> themes,boolean nationwide,List<Long> purposeDestinationIds,
                            List<String> purposeThemes) {
        return pick(matched,themes,nationwide,List.of(),purposeDestinationIds,purposeThemes);
    }

    /**
     * suggestedDestinationIds는 지역을 정하지 않은 요청에서 AI가 제안하고 서버가 관광공사 등록 장소로 근거를 확인한 여행지다.
     * 원하는 여행을 할 수 있는 곳으로 확인했으므로 시·도가 겹쳐도 제안 순서대로 먼저 넣고, 남는 자리를 아래 기준으로 채운다.
     */
    static List<Place> pick(List<Place> matched,List<String> themes,boolean nationwide,List<Long> suggestedDestinationIds,
                            List<Long> purposeDestinationIds,List<String> purposeThemes) {
        if(matched.size()<=MAX_CANDIDATES) return matched;
        var ranked=new ArrayList<>(matched);
        ranked.sort(Comparator.comparingInt((Place p)->purposeDestinationIds.contains(p.getId())?0:1)
            .thenComparingInt(p->-themeScore(p,themes)).thenComparingInt(p->-themeScore(p,purposeThemes)).thenComparing(Place::getId));
        List<Place> chosen=new ArrayList<>(); Set<String> provinces=new HashSet<>();
        for(Long id:suggestedDestinationIds) for(var place:matched)
            if(place.getId().equals(id) && chosen.size()<MAX_CANDIDATES && !chosen.contains(place)) { chosen.add(place); provinces.add(province(place)); }
        // 목적과 가까운 분류가 여러 개면(예: AI가 제안한 방향이 자연·바다·문화·역사) 분류마다 한 곳씩 먼저 골라
        // 후보가 한 종류의 여행지로만 채워지지 않게 한다.
        if(purposeThemes.size()>1)
            for(String theme:purposeThemes) for(var place:ranked) {
                if(chosen.size()==MAX_CANDIDATES || chosen.contains(place) || themeScore(place,List.of(theme))==0) continue;
                if(nationwide && !provinces.add(province(place))) continue;
                chosen.add(place); break;
            }
        if(nationwide)
            for(var place:ranked) if(chosen.size()<MAX_CANDIDATES && provinces.add(province(place))) chosen.add(place);
        for(var place:ranked) if(chosen.size()<MAX_CANDIDATES && !chosen.contains(place)) chosen.add(place);
        return List.copyOf(chosen);
    }

    /** 여행지 분류(subtype, 예: "바다,맛집,자연") 중 사용자가 고른 여행 취향과 겹치는 수. */
    static int themeScore(Place place,List<String> themes) {
        if(place.getSubtype()==null || themes.isEmpty()) return 0;
        var tags=Arrays.stream(place.getSubtype().split(",")).map(String::strip).toList();
        return (int)themes.stream().filter(tags::contains).count();
    }

    /**
     * 조건의 지역과 여행지가 맞는지 본다. 시·도 이름만 쓰면 그 시·도의 여행지 전체가 맞고,
     * 여행지 이름·시군 이름은 그대로 또는 시·도 이름을 앞에 붙여 쓸 수 있다. 예) 강원·강릉·강릉시·강원도강릉시.
     */
    static boolean matches(Place place,String query) {
        String[] parts=place.getRegion().strip().split("\\s+");
        String province=Provinces.canonical(parts[0]);
        if(province==null) return false;
        String city=parts.length>1?parts[1]:null;
        if(province.equals(Provinces.canonical(query))) return true;
        var local=new ArrayList<String>(List.of(place.getName())); if(city!=null) local.add(city);
        if(local.contains(query)) return true;
        for(String name:Provinces.names(province))
            if(query.startsWith(name) && local.contains(query.substring(name.length()))) return true;
        return false;
    }

    private static String province(Place place) {
        return Provinces.canonical(place.getRegion().strip().split("\\s+")[0]);
    }

    // https 공식 관광 사이트만 출처로 인정한다. 시·군청 사이트는 .go.kr 주소를 쓴다.
    private boolean safeSource(String url) {
        try {
            URI uri=URI.create(url);
            String host=uri.getHost();
            return "https".equals(uri.getScheme()) && uri.getUserInfo()==null && host!=null
                && (host.endsWith(".go.kr") || OFFICIAL_HOSTS.contains(host));
        } catch (RuntimeException e) { return false; }
    }
}
