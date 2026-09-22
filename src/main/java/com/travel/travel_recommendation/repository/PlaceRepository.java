package com.travel.travel_recommendation.repository;
import com.travel.travel_recommendation.entity.Place;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
public interface PlaceRepository extends Repository<Place,Long> {
    Place save(Place place);
    void flush();
    List<Place> findAllById(Iterable<Long> ids);
    Optional<Place> findBySourceProviderAndSourcePlaceId(String provider,String sourceId);
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Place p where p.id=:id and p.placeType='DESTINATION'")
    Optional<Place> lockDestination(Long id);
    @Query("select p from Place p where p.active=true and p.placeType='DESTINATION' order by p.id")
    List<Place> findDestinations();
    Optional<Place> findById(Long id);
    // details_json처럼 여러 기능이 나눠 쓰는 값을 고칠 때 다른 쓰기와 겹치지 않게 잠근다.
    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Place p where p.id=:id")
    Optional<Place> lockById(Long id);
    // 즐겨찾기 목록처럼 여러 장소를 한 번에 읽을 때 사용한다.
    List<Place> findByIdIn(java.util.Collection<Long> ids);

    @Query("select p from Place p where p.active=true and p.region=:region "
        + "and p.placeType in ('ATTRACTION','ACCOMMODATION','RESTAURANT') order by p.name,p.id")
    List<Place> findLocalPlaces(String region);
}
