package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.Input;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NearbyRestaurantServiceTests {
    private PlaceCandidate place(long id,Double longitude,Double latitude) {
        return new PlaceCandidate(id,"장소"+id,"강원 강릉시 경포로 "+id,"유형","","https://place.map.kakao.com/"+id,"",
            LocalDateTime.of(2026,9,1,0,0),longitude,latitude,"KAKAO");
    }

    // 음식점을 찾을 지점은 고른 관광지와 숙소 후보다. 좌표가 없는 곳은 빼고, 1.5km 안에 이미 찾을 지점이 있으면 다시 찾지 않는다.
    @Test void searchPointsSkipCloseAndUnlocatedPlaces() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,null,2,true,false,List.of(),"");
        // 경도 0.01은 약 0.9km, 0.05는 약 4.4km다.
        var pool=new PlaceCandidateView(UUID.randomUUID(),new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0)),2,true,
            List.of(place(1,128.90,37.7),place(2,128.91,37.7),place(3,null,null),place(4,128.95,37.7)),
            List.of(place(5,128.90,37.7),place(6,129.00,37.7)),List.of());
        var input=new Input(pool.jobId(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
        assertThat(NearbyRestaurantService.points(input)).extracting(PlaceCandidate::placeId).containsExactly(1L,4L,6L);
    }
}
