package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.PlaceRating;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;

public interface PlaceRatingRepository extends JpaRepository<PlaceRating,Long> {
    Optional<PlaceRating> findByUserIdAndPlaceId(Long userId, Long placeId);
    // 갱신·삭제는 같은 평가를 동시에 바꾸지 않도록 행을 잠그고 처리한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from PlaceRating r where r.userId=:userId and r.placeId=:placeId")
    Optional<PlaceRating> lockByUserIdAndPlaceId(Long userId, Long placeId);
    List<PlaceRating> findByUserIdOrderByUpdatedAtDescIdDesc(Long userId);
    /** 장소별 평균 별점과 평가 수. 태그 조인 전에 별점만 따로 집계해 중복 계산을 막는다. */
    @Query("select r.placeId, avg(r.score * 1.0), count(r) from PlaceRating r where r.placeId in :placeIds group by r.placeId")
    List<Object[]> summarize(Collection<Long> placeIds);
}
