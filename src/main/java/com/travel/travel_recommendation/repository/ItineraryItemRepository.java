package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.ItineraryItem;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ItineraryItemRepository extends JpaRepository<ItineraryItem,Long> {
    List<ItineraryItem> findByItineraryIdOrderByDayNoAscSequenceNoAsc(Long itineraryId);
    void deleteByItineraryId(Long itineraryId);
    List<ItineraryItem> findByItineraryIdInAndItemType(java.util.Collection<Long> itineraryIds, String itemType);
}
