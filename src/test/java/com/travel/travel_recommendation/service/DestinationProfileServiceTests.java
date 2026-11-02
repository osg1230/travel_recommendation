package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DestinationProfileServiceTests {
    private final PlaceRepository places = mock(PlaceRepository.class);
    private final TourApiClient tour = mock(TourApiClient.class);
    private final DestinationProfileService service = new DestinationProfileService(places, tour, new JsonMapper(), mock(PlatformTransactionManager.class));

    private Place destination() throws Exception {
        var place = Place.fromKakao(new KakaoPlace("k1", "ATTRACTION", "강릉", "강원특별자치도 강릉시", "주소", "", 128.9, 37.8, "", LocalDateTime.now()));
        Field type = Place.class.getDeclaredField("placeType");
        type.setAccessible(true); type.set(place, "DESTINATION");
        return place;
    }

    // 관광공사 오류로 목록을 만들지 못한 여행지는 추천이 끝날 때마다 다시 부르지 않고 하루 뒤로 미룬다.
    @Test void failedDestinationIsNotBuiltAgainTheSameDay() throws Exception {
        when(places.findById(1L)).thenReturn(Optional.of(destination()));
        when(tour.configured()).thenReturn(true);
        when(tour.regionCodes("강원특별자치도 강릉시")).thenThrow(new RecommendationException("TOUR_QUOTA", "한도"));
        assertThat(service.profile(1L)).isEmpty();
        assertThat(service.profile(1L)).isEmpty();
        verify(tour, times(1)).regionCodes("강원특별자치도 강릉시");
    }
}
