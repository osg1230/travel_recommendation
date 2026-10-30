package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.RecommendationRequest;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecommendationRequestRepository extends JpaRepository<RecommendationRequest,Long> {
    // 추천 이력 목록은 최근 100건까지 최신순으로 보여 준다.
    List<RecommendationRequest> findTop100ByUserIdOrderByCreatedAtDescIdDesc(Long userId);
    Optional<RecommendationRequest> findByIdAndUserId(Long id, Long userId);
    // 비슷한 조건의 다른 회원 요청을 찾을 때 최근 요청부터 읽는다(SimilarMemberService).
    List<RecommendationRequest> findTop500ByOrderByCreatedAtDescIdDesc();
}
