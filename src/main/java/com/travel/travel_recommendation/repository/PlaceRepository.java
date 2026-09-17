package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Place;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
public interface PlaceRepository extends Repository<Place,Long> {
    @Query("select p from Place p where p.active=true and p.placeType='DESTINATION' order by p.id")
    List<Place> findDestinations();
    Optional<Place> findById(Long id);

    @Query("select p from Place p where p.active=true and p.region=:region "
        + "and p.placeType in ('ATTRACTION','ACCOMMODATION','RESTAURANT') order by p.name,p.id")
    List<Place> findLocalPlaces(String region);
}
