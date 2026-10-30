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

    /**
     * 이미 등록한 장소면 false를 돌려주고 새 행을 만들지 않는다.
     * 다른 탭에서 거의 동시에 등록하면 DB 중복 방지(UQ_favorites_user_place)에 걸려 DataIntegrityViolationException이 나고,
     * 이 트랜잭션은 되돌려진다. 컨트롤러가 이를 '이미 즐겨찾기에 있음'으로 안내한다.
     */
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

    /** 지운 즐겨찾기의 장소 이름과, 되돌리기(다시 등록)를 할 수 있는 활성 장소인지 여부. */
    public record Removed(String placeName, boolean restorable) {}

    /**
     * 본인 즐겨찾기만 지운다. 지울 것이 없으면(두 번 누름·다른 탭에서 먼저 삭제) 빈 값을 돌려주며 오류로 보지 않는다.
     * 비활성 장소의 즐겨찾기도 지울 수 있다.
     */
    @Transactional(timeout=10)
    public Optional<Removed> remove(Long placeId, String loginId) {
        if(favorites.deleteByUserAndPlace(member(loginId),placeId)==0) return Optional.empty();
        var place=places.findById(placeId);
        return Optional.of(new Removed(place.map(Place::getName).orElse("장소"),
            place.map(p->p.isActive() && ALLOWED_TYPES.contains(p.getPlaceType())).orElse(false)));
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
            // 장소 행이 없으면 보여 줄 정보가 없어 뺀다. 비활성(더 이상 확인되지 않는) 장소는 표시만 바꿔 남겨 삭제할 수 있게 한다.
            if(place==null) continue;
            result.add(new FavoritePlace(favorite.getId(),place.getId(),place.getPlaceType(),typeName(place.getPlaceType()),
                place.getName(),place.getRegion(),place.getAddress(),place.getSourceUrl(),place.getMapUrl(),favorite.getCreatedAt(),place.isActive()));
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
