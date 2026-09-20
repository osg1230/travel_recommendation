package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class PlaceOperatingServiceTests {
    private final PlaceCandidate place = new PlaceCandidate(1L, "오죽헌·시립박물관", "강원특별자치도 강릉시 율곡로3139번길 24", "", "", "https://www.gn.go.kr", "", LocalDateTime.now());
    private TourApiClient.SearchPlace found(String name, String address, String type) { return new TourApiClient.SearchPlace("129784", type, name, address, 128.8796, 37.7791); }
    @Test void requiresMatchingNameRoadNumberAndType() {
        assertThat(PlaceOperatingService.matches(place, "ATTRACTION", "강릉", found("강릉 오죽헌·시립박물관", place.address() + " (죽헌동)", "14"))).isTrue();
        assertThat(PlaceOperatingService.matches(place, "ATTRACTION", "강릉", found(place.name(), place.address() + "-1", "14"))).isFalse();
        assertThat(PlaceOperatingService.matches(place, "ATTRACTION", "강릉", found("오죽헌 전통뱃놀이", place.address(), "28"))).isFalse();
        assertThat(PlaceOperatingService.matches(place, "ATTRACTION", "강릉", found(place.name(), place.address(), "39"))).isFalse();
        var located = new PlaceCandidate(1L, place.name(), place.address(), "", "", "", "", place.verifiedAt(), 129.0, 37.0, "KAKAO");
        assertThat(PlaceOperatingService.matches(located, "ATTRACTION", "강릉", found(place.name(), place.address(), "14"))).isFalse();
    }
    @Test void accessFailureAndAmbiguityCannotWriteOrFetchIntro() {
        var candidates = mock(PlaceCandidateService.class); var repo = mock(PlaceRepository.class); var tour = mock(TourApiClient.class);
        var tx = mock(PlatformTransactionManager.class);
        var service = new PlaceOperatingService(candidates, repo, tour, new JsonMapper(), tx);
        var id = UUID.randomUUID();
        when(candidates.find(id, 2, null, null)).thenThrow(new IllegalArgumentException("denied"));
        assertThatThrownBy(() -> service.refresh(id, 2, 1, null, null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(tour, repo, tx);
        var destination = new DestinationCandidate(2L, "강릉", "강원특별자치도 강릉시", "", "", LocalDateTime.now());
        doReturn(new PlaceCandidateView(id, destination, 1, false, List.of(place), List.of(), List.of())).when(candidates).find(id, 2, null, null);
        var result = found(place.name(), place.address(), "14");
        when(tour.search(place.name())).thenReturn(List.of(result, result));
        assertThat(service.refresh(id, 2, 1, null, null)).isFalse();
        verify(tour, never()).findOperatingInfo(anyString(), anyString()); verifyNoInteractions(repo, tx);
    }
    @Test void detailsPreserveOtherFieldsAndInvalidateChangedIdentity() {
        var mapper = new JsonMapper();
        var data = new KakaoPlace("1", "ATTRACTION", place.name(), "강원특별자치도 강릉시", place.address(), "", 128.8796, 37.7791, "https://place.map.kakao.com/1", LocalDateTime.now());
        var entity = Place.fromKakao(data); entity.updateDetails("{\"foods\":[\"기존 정보\"]}");
        var info = new PlaceOperatingInfo("129784", "14", "09:00~18:00", "연중무휴", "", "", Instant.now());
        entity.updateDetails(PlaceOperatingDetails.write(entity, info, mapper));
        assertThat(mapper.readTree(entity.getDetailsJson()).path("foods").get(0).asText()).isEqualTo("기존 정보");
        assertThat(PlaceOperatingDetails.read(entity, mapper)).isEqualTo(info);
        entity.refreshKakao(new KakaoPlace("1", "ATTRACTION", "변경된 장소", data.region(), data.address(), "", data.longitude(), data.latitude(), data.url(), data.checkedAt()));
        assertThat(PlaceOperatingDetails.read(entity, mapper)).isNull();
    }
}
