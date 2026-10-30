package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.MemberStats;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 비슷한 조건으로 여행한 다른 회원이 실제로 고른 장소를 센다(중간발표 향후 계획 2단계, 2026-10-31).
 * 비슷한 요청은 여행 취향·동행자·계절 키워드와 지역·기간이 겹치는 수가 2 이상인 다른 회원의 추천 요청이며, 최근 요청부터 최대 20건을 쓴다.
 * 협업 필터링 같은 알고리즘 없이 겹치는 수를 세는 규칙이라 같은 조건과 같은 기록이면 늘 같은 결과가 나온다.
 * 장소마다 회원 수만 세고 닉네임·로그인 ID·입력 원문·한줄평은 읽지 않는다. 참고할 기록이 없으면 빈 결과라 추천은 지금처럼 동작한다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class SimilarMemberService {
    /** 비슷한 요청으로 보는 최소 점수, 참고할 비슷한 요청 수, 비슷한지 살펴볼 최근 요청 수. */
    static final int MIN_SCORE=2, MAX_REQUESTS=20, SCAN_REQUESTS=500;
    /** 비교하는 키워드 분류. 이동수단은 여행지 고르기와 관련이 적어 비교하지 않는다. */
    private static final Set<String> COMPARED=Set.of("THEME","COMPANION","SEASON");
    private final RecommendationRequestRepository requests;
    private final UserRepository users;
    private final ItineraryRepository itineraries;
    private final ItineraryItemRepository items;
    private final FavoriteRepository favorites;
    private final PlaceRatingRepository ratings;
    private final ObjectMapper mapper;

    /** 여행지별 기록: 비슷한 요청에서 이어진 저장 일정의 여행지와, 그 회원들의 즐겨찾기·4점 이상 평가. userId는 본인 기록을 빼는 데 쓴다(비회원은 null). */
    @Transactional(readOnly=true,timeout=10)
    public Map<Long,MemberStats> destinations(ConfirmedTravelConditions conditions, Long userId, Collection<Long> placeIds) {
        var similar=similar(conditions,userId);
        if(similar.isEmpty() || placeIds.isEmpty()) return Map.of();
        Map<Long,Set<Long>> saved=new HashMap<>();
        for(var itinerary:itineraries.findByRequestIdIn(similar.stream().map(RecommendationRequest::getId).toList()))
            if(placeIds.contains(itinerary.getDestinationPlaceId()))
                saved.computeIfAbsent(itinerary.getDestinationPlaceId(),k->new HashSet<>()).add(itinerary.getUserId());
        return stats(members(similar),placeIds,saved);
    }

    /** 관광지별 기록: 비슷한 요청에서 이어진 저장 일정의 관광 항목과, 그 회원들의 즐겨찾기·4점 이상 평가. */
    @Transactional(readOnly=true,timeout=10)
    public Map<Long,MemberStats> places(ConfirmedTravelConditions conditions, Long userId, Collection<Long> placeIds) {
        var similar=similar(conditions,userId);
        if(similar.isEmpty() || placeIds.isEmpty()) return Map.of();
        Map<Long,Long> owners=new HashMap<>();
        for(var itinerary:itineraries.findByRequestIdIn(similar.stream().map(RecommendationRequest::getId).toList()))
            owners.put(itinerary.getId(),itinerary.getUserId());
        Map<Long,Set<Long>> saved=new HashMap<>();
        if(!owners.isEmpty())
            for(var item:items.findByItineraryIdInAndItemType(owners.keySet(),"VISIT"))
                if(item.getPlaceId()!=null && placeIds.contains(item.getPlaceId()))
                    saved.computeIfAbsent(item.getPlaceId(),k->new HashSet<>()).add(owners.get(item.getItineraryId()));
        return stats(members(similar),placeIds,saved);
    }

    // 저장·즐겨찾기·높은 평가를 장소마다 회원 수로 센다. 한 회원이 같은 장소를 여러 번 골라도 한 명이다.
    private Map<Long,MemberStats> stats(Set<Long> members, Collection<Long> placeIds, Map<Long,Set<Long>> saved) {
        Map<Long,Set<Long>> favorited=new HashMap<>(), ratedHigh=new HashMap<>();
        for(var favorite:favorites.findByUserIdInAndPlaceIdIn(members,placeIds))
            favorited.computeIfAbsent(favorite.getPlaceId(),k->new HashSet<>()).add(favorite.getUserId());
        for(var rating:ratings.findByUserIdInAndPlaceIdIn(members,placeIds))
            if(rating.getScore()>=4) ratedHigh.computeIfAbsent(rating.getPlaceId(),k->new HashSet<>()).add(rating.getUserId());
        Map<Long,MemberStats> result=new HashMap<>();
        for(Long id:placeIds) {
            var stats=new MemberStats(count(saved,id,members),count(favorited,id,members),count(ratedHigh,id,members));
            if(!stats.empty()) result.put(id,stats);
        }
        return result;
    }
    private static int count(Map<Long,Set<Long>> byPlace, Long placeId, Set<Long> members) {
        return (int)byPlace.getOrDefault(placeId,Set.of()).stream().filter(members::contains).count();
    }
    private static Set<Long> members(List<RecommendationRequest> similar) {
        var ids=new HashSet<Long>();
        similar.forEach(r->ids.add(r.getUserId()));
        return ids;
    }

    /** 비슷한 다른 회원의 추천 요청(최근 순, 최대 20건). 본인 요청과 이용할 수 없는 계정의 요청은 뺀다. */
    List<RecommendationRequest> similar(ConfirmedTravelConditions conditions, Long userId) {
        var keywords=keywords(conditions);
        var found=new ArrayList<RecommendationRequest>();
        for(var request:requests.findTop500ByOrderByCreatedAtDescIdDesc()) {
            if(request.getUserId().equals(userId)) continue;
            if(score(keywords,conditions.region(),conditions.durationDays(),request)>=MIN_SCORE) found.add(request);
            if(found.size()==MAX_REQUESTS) break;
        }
        if(found.isEmpty()) return List.of();
        var active=new HashSet<Long>();
        users.findAllById(members(found)).forEach(u->{ if(u.getStatus()==UserStatus.ACTIVE) active.add(u.getId()); });
        return found.stream().filter(r->active.contains(r.getUserId())).toList();
    }

    /** 키워드 겹침 수 + 같은 지역이면 1 + 같은 기간이면 1. */
    int score(Set<String> keywords, String region, Integer durationDays, RecommendationRequest other) {
        var overlap=new HashSet<>(keywords);
        overlap.retainAll(keywords(other.getConditionsJson()));
        int score=overlap.size();
        if(sameRegion(region,other.getRegion())) score++;
        if(durationDays!=null && other.getDurationDays()!=null && durationDays==other.getDurationDays().intValue()) score++;
        return score;
    }

    // 키워드는 ID로 비교한다. ID가 없는 예전 값은 분류와 이름으로 비교한다.
    private static Set<String> keywords(ConfirmedTravelConditions conditions) {
        var keys=new HashSet<String>();
        for(var k:conditions.selectedKeywords())
            if(COMPARED.contains(k.categoryCode())) keys.add(key(k.categoryCode(),k.id(),k.name()));
        return keys;
    }
    private Set<String> keywords(String conditionsJson) {
        var keys=new HashSet<String>();
        for(var k:mapper.readTree(conditionsJson).path("selected_keywords")) {
            String category=k.path("category_code").asText("");
            if(COMPARED.contains(category))
                keys.add(key(category,k.path("keyword_id").isNumber()?k.path("keyword_id").asLong():null,k.path("name").asText("")));
        }
        return keys;
    }
    private static String key(String category, Long id, String name) {
        return id!=null?"#"+id:category+":"+name;
    }
    // "강원"과 "강원특별자치도"처럼 같은 시·도 이름도 같은 지역으로 본다. 비어 있으면(전국) 비교하지 않는다.
    private static boolean sameRegion(String mine, String other) {
        if(mine==null || other==null || mine.isBlank() || other.isBlank()) return false;
        String a=mine.replaceAll("\\s+",""), b=other.replaceAll("\\s+","");
        if(a.equals(b)) return true;
        String province=Provinces.canonical(a);
        return province!=null && province.equals(Provinces.canonical(b));
    }
}
