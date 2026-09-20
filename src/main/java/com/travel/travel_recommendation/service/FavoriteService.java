package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.FavoritePlace;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** 회원의 장소 즐겨찾기 등록·조회·삭제를 처리한다. 여행지·관광지·숙소만 등록한다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class FavoriteService {
    private final FavoriteRepository favorites;
    private final PlaceRepository places;
    private final UserRepository users;
    private static final Set<String> ALLOWED_TYPES=Set.of("DESTINATION","ATTRACTION","ACCOMMODATION");

    /** 이미 등록한 장소면 false를 돌려주고 새 행을 만들지 않는다. */
    @Transactional(timeout=10)
    public boolean add(Long placeId, String loginId) {
        Long userId=member(loginId);
        var place=places.findById(placeId).filter(Place::isActive)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"장소를 찾을 수 없어요."));
        if(!ALLOWED_TYPES.contains(place.getPlaceType()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"음식점은 즐겨찾기에 등록할 수 없어요. 여행지·관광지·숙소만 등록할 수 있어요.");
        if(favorites.existsByUserIdAndPlaceId(userId,placeId)) return false;
        favorites.saveAndFlush(new Favorite(userId,placeId));
        return true;
    }

    @Transactional(timeout=10)
    public void remove(Long placeId, String loginId) {
        var favorite=favorites.findByUserIdAndPlaceId(member(loginId),placeId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"등록한 즐겨찾기를 찾을 수 없어요."));
        favorites.delete(favorite);
        favorites.flush();
    }

    @Transactional(readOnly=true,timeout=10)
    public List<FavoritePlace> list(String loginId) {
        var registered=favorites.findByUserIdOrderByIdDesc(member(loginId));
        var found=new HashMap<Long,Place>();
        if(registered.isEmpty()) return List.of();
        places.findByIdIn(registered.stream().map(Favorite::getPlaceId).toList()).forEach(p->found.put(p.getId(),p));
        var result=new ArrayList<FavoritePlace>();
        for(var favorite:registered) {
            var place=found.get(favorite.getPlaceId());
            // 장소가 지워졌거나 비활성이면 목록에서 제외하고 등록 행은 그대로 둔다.
            if(place==null || !place.isActive()) continue;
            result.add(new FavoritePlace(favorite.getId(),place.getId(),place.getPlaceType(),typeName(place.getPlaceType()),
                place.getName(),place.getRegion(),place.getAddress(),place.getSourceUrl(),place.getMapUrl(),favorite.getCreatedAt()));
        }
        return result;
    }

    /** 장소 목록 화면에서 등록 여부를 표시할 때 사용한다. 비회원은 빈 집합이다. */
    @Transactional(readOnly=true,timeout=10)
    public Set<Long> registeredPlaceIds(String loginId) {
        if(loginId==null) return Set.of();
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE).map(User::getId)
            .map(id->favorites.findByUserIdOrderByIdDesc(id).stream().map(Favorite::getPlaceId).collect(java.util.stream.Collectors.toSet()))
            .orElseGet(Set::of);
    }

    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
    private String typeName(String placeType) {
        return switch(placeType) {
            case "DESTINATION" -> "여행지";
            case "ATTRACTION" -> "관광지";
            default -> "숙소";
        };
    }
}
