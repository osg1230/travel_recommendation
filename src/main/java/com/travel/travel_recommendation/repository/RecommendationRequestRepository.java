package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.RecommendationRequest;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequest,Long> {}
