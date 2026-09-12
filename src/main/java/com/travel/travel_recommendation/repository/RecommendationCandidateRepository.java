package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.RecommendationCandidate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendationCandidateRepository extends JpaRepository<RecommendationCandidate,Long> {
    List<RecommendationCandidate> findByRequestIdOrderByRank(Long requestId);
}
