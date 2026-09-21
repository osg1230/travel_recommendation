package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Itinerary;
import java.time.LocalDate;
import java.util.*;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.*;

public interface ItineraryRepository extends JpaRepository<Itinerary,Long> {
    List<Itinerary> findByUserIdOrderByStartDateDescIdDesc(Long userId);
    Optional<Itinerary> findByIdAndUserId(Long id, Long userId);
    // 수정안 적용은 같은 일정을 동시에 덮어쓰지 않도록 행을 잠그고 처리한다.
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from Itinerary i where i.id=:id and i.userId=:userId")
    Optional<Itinerary> lockByIdAndUserId(Long id, Long userId);
    // 같은 일정 초안을 두 번 저장했는지 확인할 때 후보를 좁힌다.
    List<Itinerary> findByUserIdAndDestinationPlaceIdAndStartDate(Long userId, Long destinationPlaceId, LocalDate startDate);
}
