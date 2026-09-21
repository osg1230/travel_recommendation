package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.PlaceRatingTag;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface PlaceRatingTagRepository extends JpaRepository<PlaceRatingTag,PlaceRatingTag.Key> {
    List<PlaceRatingTag> findByRatingIdOrderBySlot(Long ratingId);
    List<PlaceRatingTag> findByRatingIdIn(Collection<Long> ratingIds);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from PlaceRatingTag t where t.ratingId=:ratingId")
    int deleteByRating(Long ratingId);
    /** 장소별 태그 선택 횟수. */
    @Query("select r.placeId, t.tagId, count(t) from PlaceRatingTag t join PlaceRating r on r.id=t.ratingId where r.placeId in :placeIds group by r.placeId, t.tagId")
    List<Object[]> countTags(Collection<Long> placeIds);
}
