package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.FavoriteRepository;
import com.travel.travel_recommendation.repository.PlaceRatingRepository;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 내 기록 취향(2026-11-09, docs/quality/2026-11-09_내기록_취향점수_계획.md). 회원이 즐겨찾기했거나 4점 이상으로 평가한 관광지가
 * 어떤 종류(자연·바다·휴식·문화·역사·체험·액티비티)인지 센다. "좋아한 곳과 비슷한 곳"을 고르는 콘텐츠 기반 추천이다.
 * 다른 회원 기록이 필요 없어 기록이 없는 회원·비회원은 빈 결과이고, 그때 추천은 지금처럼 동작한다.
 * 종류는 일정의 취향 판단과 같은 규칙(ThemePlaces)으로 보며, 이번에 고른 취향으로 줄이지 않는다(입력하지 않은 취향을 찾기 위해서다).
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class MyTasteService {
    /** 좋아한 곳으로 보는 최소 별점. 비슷한 회원 기록(SimilarMemberService)의 '4점 이상 평가'와 같다. */
    static final int HIGH_SCORE=4;
    private final FavoriteRepository favorites;
    private final PlaceRatingRepository ratings;
    private final PlaceRepository places;

    /** 종류별 좋아한 관광지 수(예: 자연 3, 휴식 1). 비회원이거나 기록이 없으면 빈 값이다. */
    @Transactional(readOnly=true,timeout=10)
    public Map<String,Integer> themeCounts(Long userId) {
        if(userId==null) return Map.of();
        // 즐겨찾기와 4점 이상 평가를 합친다. 같은 곳이 둘 다에 있어도 한 곳으로 센다.
        Set<Long> likedIds=new LinkedHashSet<>();
        favorites.findByUserIdOrderByIdDesc(userId).forEach(favorite->likedIds.add(favorite.getPlaceId()));
        ratings.findByUserIdOrderByUpdatedAtDescIdDesc(userId).stream()
            .filter(rating->rating.getScore()>=HIGH_SCORE).forEach(rating->likedIds.add(rating.getPlaceId()));
        if(likedIds.isEmpty()) return Map.of();
        return countThemes(places.findAllById(likedIds));
    }

    /** 관광지만 종류를 센다. 여행지·숙소·음식점 즐겨찾기는 빼고, 어느 종류에도 맞지 않는 곳은 세지 않는다. 한 곳이 두 종류에 맞으면 둘 다 센다. */
    static Map<String,Integer> countThemes(Iterable<Place> liked) {
        Map<String,Integer> counts=new LinkedHashMap<>();
        for(var place:liked) {
            if(!"ATTRACTION".equals(place.getPlaceType())) continue;
            ThemePlaces.matched(place.getName(),place.getSubtype(),ThemePlaces.allPlaceThemes())
                .forEach(theme->counts.merge(theme,1,Integer::sum));
        }
        return counts;
    }
}
