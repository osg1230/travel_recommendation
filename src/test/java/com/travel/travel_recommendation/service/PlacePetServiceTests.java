package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PlacePetServiceTests {
    private final JsonMapper mapper = new JsonMapper();
    private final PlaceRepository repo = mock(PlaceRepository.class);
    private final TourApiClient tour = mock(TourApiClient.class);
    private final PlacePetService service = new PlacePetService(repo, tour, mapper, mock(PlatformTransactionManager.class));
    private static final String ADDRESS = "강원특별자치도 강릉시 창해로 514";
    private final PlaceCandidate beach = new PlaceCandidate(1L, "경포해변", ADDRESS, "", "", "https://www.gn.go.kr", "", LocalDateTime.now());
    private final PlaceCandidate diner = new PlaceCandidate(2L, "바다식당", "강원특별자치도 강릉시 창해로 20", "", "", "https://place.map.kakao.com/2", "", LocalDateTime.now());

    private Input input() {
        var conditions = new ConfirmedTravelConditions("TEXT", "강아지랑", "강릉", null, null, 1, null, 2, false, null, List.of(), "");
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(9L, "강릉", "강원특별자치도 강릉시", "", "", LocalDateTime.now()),
            1, false, List.of(beach), List.of(), List.of(diner));
        return new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90);
    }
    private static Result draft() {
        var visit = new Item("VISIT", 1L, "경포해변", LocalTime.of(9, 0), LocalTime.of(10, 30), "", "", "", null, null);
        var meal = new Item("MEAL", 2L, "바다식당", LocalTime.of(12, 0), LocalTime.of(13, 0), "", "", "", null, null);
        var again = new Item("VISIT", 1L, "경포해변", LocalTime.of(14, 0), LocalTime.of(15, 0), "", "", "", null, null);
        return new Result("강릉", List.of(new Day(1, null, List.of(visit, meal, again))), KakaoMapClient.Transport.CAR, Instant.now());
    }
    private static Place entity(PlaceCandidate c) {
        return Place.fromKakao(new KakaoPlace("k" + c.placeId(), "ATTRACTION", c.name(), "강원특별자치도 강릉시", c.address(), "", 128.9, 37.8, c.sourceUrl(), LocalDateTime.now()));
    }

    // 관광공사에서 이름·도로명이 하나로 맞는 장소만 조회하고, 같은 장소는 한 번만 묻는다. 둘 이상 맞거나 없으면 정보 없음(null)으로 둔다.
    @Test void looksUpOnlyUniquelyMatchedPlacesOnce() {
        when(repo.findById(anyLong())).thenReturn(Optional.empty());
        var found = new TourApiClient.SearchPlace("129784", "12", "경포해변", ADDRESS, 128.9, 37.8);
        when(tour.search("경포해변")).thenReturn(List.of(found));
        when(tour.search("바다식당")).thenReturn(List.of());
        var info = new PlacePetInfo("129784", "전구역 동반가능", "전 견종", "목줄 착용", "", Instant.now());
        when(tour.findPetInfo("129784")).thenReturn(Optional.of(info));

        var checks = service.lookup(input(), draft(), Instant.now().plusSeconds(30));
        assertThat(checks).extracting(PetCheck::placeId).containsExactly(1L, 2L);
        assertThat(checks.get(0).info()).isEqualTo(info);
        assertThat(checks.get(1).info()).isNull();
        verify(tour, times(1)).search("경포해변"); verify(tour, times(1)).findPetInfo("129784");
    }

    // 관광공사에서 찾지 못한 장소는 7일 동안 초안마다 다시 검색하지 않는다. 이름이 바뀌었거나 기간이 지나면 다시 찾는다.
    @Test void placesNotFoundAreNotSearchedAgainForAWeek() {
        var place = entity(beach);
        var now = Instant.now();
        place.updateDetails(PlacePetDetails.writeMiss(place, now.minus(Duration.ofDays(1)), mapper));
        assertThat(PlacePetDetails.missedRecently(place, now, mapper)).isTrue();
        assertThat(PlacePetDetails.missedRecently(place, now.plus(Duration.ofDays(7)), mapper)).isFalse();
        when(repo.findById(1L)).thenReturn(Optional.of(place));
        when(repo.findById(2L)).thenReturn(Optional.empty());
        when(tour.search("바다식당")).thenReturn(List.of());

        var checks = service.lookup(input(), draft(), Instant.now().plusSeconds(30));
        assertThat(checks.get(0).info()).isNull();
        verify(tour, never()).search("경포해변");
        verify(tour, times(1)).search("바다식당");
    }

    // 해변처럼 카카오 주소가 지번이거나 도로명 번호가 달라도, 이름이 같고 좌표가 1.5km 안이면 같은 장소로 본다. 이름이 다르거나 멀면 아니다.
    @Test void matchesSameNamedPlacesByRoadAddressOrNearbyCoordinates() {
        var anmok = new PlaceCandidate(5L, "안목해변", "강원특별자치도 강릉시 창해로14번길 51-26", "", "", "", "", LocalDateTime.now(), 128.9478, 37.7719, "KAKAO");
        var found = new TourApiClient.SearchPlace("1", "12", "안목해변", "강원특별자치도 강릉시 창해로14번길 20-1", 128.9482754, 37.7722617);
        assertThat(PlacePetService.matches(anmok, "ATTRACTION", "강릉", found)).isTrue();
        // 관광공사 제목의 괄호 설명은 빼고 비교한다. 지번 주소라도 좌표가 가까우면 된다.
        var sageunjin = new PlaceCandidate(6L, "사근진해변", "강원특별자치도 강릉시 안현동 산 1", "", "", "", "", LocalDateTime.now(), 128.8990, 37.8125, "KAKAO");
        assertThat(PlacePetService.matches(sageunjin, "ATTRACTION", "강릉", new TourApiClient.SearchPlace("2", "12", "사근진해변(사근진해수욕장)", "강원특별자치도 강릉시 해안로604번길 16", 128.8988, 37.8127))).isTrue();
        // 좌표가 없으면 도로명이 같아야 하고, 이름이 다르거나(화장실) 멀면 다른 장소다.
        assertThat(PlacePetService.matches(beach, "ATTRACTION", "강릉", new TourApiClient.SearchPlace("3", "12", "경포해변", ADDRESS, 128.9, 37.8))).isTrue();
        assertThat(PlacePetService.matches(beach, "ATTRACTION", "강릉", new TourApiClient.SearchPlace("3", "12", "경포해변", "강원특별자치도 강릉시 창해로 500", 128.9, 37.8))).isFalse();
        assertThat(PlacePetService.matches(anmok, "ATTRACTION", "강릉", new TourApiClient.SearchPlace("4", "12", "안목해변화장실", "", 128.9482, 37.7722))).isFalse();
        assertThat(PlacePetService.matches(anmok, "ATTRACTION", "강릉", new TourApiClient.SearchPlace("5", "12", "안목해변", "", 128.98, 37.7722))).isFalse();
        assertThat(PlacePetService.matches(anmok, "RESTAURANT", "강릉", found)).isFalse();
    }

    // 7일 안에 저장해 둔 정보가 있으면 관광공사를 다시 부르지 않고, 시간 제한이 지나면 새 조회 없이 정보 없음으로 둔다.
    @Test void reusesStoredInfoAndStopsAfterDeadline() {
        var stored = new PlacePetInfo("129784", "일부구역 동반가능", "소형견", "", "", Instant.now().minus(Duration.ofDays(2)));
        var place = entity(beach); place.updateDetails(PlacePetDetails.write(place, stored, mapper));
        when(repo.findById(1L)).thenReturn(Optional.of(place));
        when(repo.findById(2L)).thenReturn(Optional.empty());

        var checks = service.lookup(input(), draft(), Instant.now().minusSeconds(1));
        assertThat(checks.get(0).info()).isEqualTo(stored);
        assertThat(checks.get(1).info()).isNull();
        verifyNoInteractions(tour);
    }
}
