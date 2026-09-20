package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Itinerary;
import java.time.LocalDate;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryRepository extends JpaRepository<Itinerary,Long> {
    List<Itinerary> findByUserIdOrderByStartDateDescIdDesc(Long userId);
    Optional<Itinerary> findByIdAndUserId(Long id, Long userId);
    // 같은 일정 초안을 두 번 저장했는지 확인할 때 후보를 좁힌다.
    List<Itinerary> findByUserIdAndDestinationPlaceIdAndStartDate(Long userId, Long destinationPlaceId, LocalDate startDate);
}
