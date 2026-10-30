package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Favorite;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface FavoriteRepository extends JpaRepository<Favorite,Long> {
    List<Favorite> findByUserIdOrderByIdDesc(Long userId);
    Optional<Favorite> findByUserIdAndPlaceId(Long userId, Long placeId);
    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);
    /** 회원의 즐겨찾기 한 건을 지우고 지운 행 수를 돌려준다. 이미 지워졌으면 0이다(두 번 누름·다른 탭). */
    @Modifying
    @Query("delete from Favorite f where f.userId=:userId and f.placeId=:placeId")
    int deleteByUserAndPlace(Long userId, Long placeId);
    List<Favorite> findByUserIdInAndPlaceIdIn(Collection<Long> userIds, Collection<Long> placeIds);
}
