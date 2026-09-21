package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.RecommendationCandidate;
import java.util.*;
import org.springframework.data.jpa.repository.*;
public interface RecommendationCandidateRepository extends JpaRepository<RecommendationCandidate,Long> {
    List<RecommendationCandidate> findByRequestIdOrderByRank(Long requestId);
    List<RecommendationCandidate> findByRequestIdInOrderByRequestIdAscRankAsc(Collection<Long> requestIds);
    @Modifying(clearAutomatically=true,flushAutomatically=true)
    @Query("delete from RecommendationCandidate c where c.requestId=:requestId")
    int deleteByRequest(Long requestId);
}
