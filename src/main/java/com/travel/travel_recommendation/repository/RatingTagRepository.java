package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.RatingTag;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RatingTagRepository extends JpaRepository<RatingTag,Long> {
    List<RatingTag> findByActiveTrueOrderBySortOrderAscIdAsc();
}
