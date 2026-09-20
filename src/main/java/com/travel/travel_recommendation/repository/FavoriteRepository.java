package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Favorite;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FavoriteRepository extends JpaRepository<Favorite,Long> {
    List<Favorite> findByUserIdOrderByIdDesc(Long userId);
    Optional<Favorite> findByUserIdAndPlaceId(Long userId, Long placeId);
    boolean existsByUserIdAndPlaceId(Long userId, Long placeId);
}
