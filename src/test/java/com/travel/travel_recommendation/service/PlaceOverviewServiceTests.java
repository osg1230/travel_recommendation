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

class PlaceOverviewServiceTests {
    private final JsonMapper mapper = new JsonMapper();
    private final PlaceRepository repo = mock(PlaceRepository.class);
    private final TourApiClient tour = mock(TourApiClient.class);
    private final PlaceOverviewService service = new PlaceOverviewService(repo, tour, mapper, mock(PlatformTransactionManager.class));
    private final PlaceCandidate temple = new PlaceCandidate(1L, "법주사", "충청북도 보은군 속리산면 법주사로 405", "", "", "https://place.map.kakao.com/1", "",
        LocalDateTime.now(), 127.8329, 36.5411, "KAKAO");
    private final PlaceCandidate pine = new PlaceCandidate(2L, "정이품송", "충청북도 보은군 속리산면 상판리 17-3", "", "", "https://place.map.kakao.com/2", "",
        LocalDateTime.now(), 127.78, 36.53, "KAKAO");
    private final TourApiClient.SearchPlace kto = new TourApiClient.SearchPlace("126009", "12", "보은 속리 정이품송", "충청북도 보은군 속리산면 법주사로 99",
        127.8157151, 36.5183139);

    private Input input() {
        var conditions = new ConfirmedTravelConditions("TEXT", "단풍 구경", "보은", null, null, 1, null, 2, false, null, List.of(), "");
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(9L, "보은", "충청북도 보은군", "", "", LocalDateTime.now()),
            1, false, List.of(temple, pine), List.of(), List.of());
        return new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90);
    }
    private static Result draft() {
        var visit = new Item("VISIT", 1L, "법주사", LocalTime.of(9, 0), LocalTime.of(10, 30), "", "", "", null, null);
        var meal = new Item("MEAL", 3L, "식당", LocalTime.of(12, 0), LocalTime.of(13, 0), "", "", "", null, null);
        var pine = new Item("VISIT", 2L, "정이품송", LocalTime.of(14, 0), LocalTime.of(15, 0), "", "", "", null, null);
        return new Result("보은", List.of(new Day(1, null, List.of(visit, meal, pine))), KakaoMapClient.Transport.CAR, Instant.now());
    }
    private static Place entity(PlaceCandidate c) {
        return Place.fromKakao(new KakaoPlace("k" + c.placeId(), "ATTRACTION", c.name(), "충청북도 보은군", c.address(), "",
            c.longitude(), c.latitude(), c.sourceUrl(), LocalDateTime.now()));
    }

    // 관광공사에서 이름·위치가 하나로 맞는 관광지만 소개문을 조회해 보관한다. 찾지 못한 곳도 '찾지 못함'으로 보관해, 다음 초안에서는 다시 부르지 않는다.
    @Test void looksUpUniquelyMatchedVisitsAndStoresTheResult() {
        var templeEntity = entity(temple); var pineEntity = entity(pine);
        when(tour.configured()).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(templeEntity));
        when(repo.findById(2L)).thenReturn(Optional.of(pineEntity));
        when(repo.lockById(1L)).thenReturn(Optional.of(templeEntity));
        when(repo.lockById(2L)).thenReturn(Optional.of(pineEntity));
        // 이름이 같은 다른 지역의 절(군위)은 멀어서 빼고, 보은 법주사만 맞는다.
        when(tour.searchAttractions("법주사")).thenReturn(List.of(
            new TourApiClient.SearchPlace("309839", "12", "법주사(군위)", "대구광역시 군위군 산성면 화산리", 128.4383, 36.2726),
            new TourApiClient.SearchPlace("125906", "12", "보은 법주사 [유네스코 세계유산]", "충청북도 보은군 속리산면 법주사로 405", 127.8329, 36.5410)));
        // 앞에 지명을 붙인 제목은 300m 안일 때만 맞는데, 이 정이품송 후보는 약 3km 떨어져 있어 찾지 못한 것으로 둔다.
        when(tour.searchAttractions("정이품송")).thenReturn(List.of(kto));
        when(tour.findOverview("125906")).thenReturn(Optional.of("속리산에 있는 절로 팔상전이 있다."));

        assertThat(service.lookup(input(), draft(), Instant.now().plusSeconds(30))).containsOnly(entry(1L, "속리산에 있는 절로 팔상전이 있다."));
        verify(tour, times(1)).findOverview("125906");
        verify(tour, never()).searchAttractions("식당");
        assertThat(PlaceOverviewDetails.read(pineEntity, Instant.now(), mapper).found()).isFalse();

        clearInvocations(tour);
        assertThat(service.lookup(input(), draft(), Instant.now().plusSeconds(30))).containsOnlyKeys(1L);
        verify(tour, never()).searchAttractions(anyString());
    }

    // 이름이 같은 곳이 없으면 앞에 지명을 붙인 관광공사 제목(보은 속리 정이품송)을 보되, 300m 안(실제 카카오 좌표와 약 100m)일 때만 같은 장소로 본다.
    @Test void prefixedTitlesMatchOnlyWhenNear() {
        var near = new PlaceCandidate(4L, "정이품송", "충북 보은군 속리산면 상판리 241", "", "", "", "", LocalDateTime.now(), 127.815581, 36.517404, "KAKAO");
        assertThat(PlaceOverviewService.prefixedTitle(near, kto)).isTrue();
        assertThat(PlaceOverviewService.prefixedTitle(pine, kto)).isFalse();
        // 이름으로 끝나지 않거나(정이품송 주차장), 이름이 그대로 같으면(같은 이름은 PlacePetService.matches가 본다) 이 대조로는 맞추지 않는다.
        assertThat(PlaceOverviewService.prefixedTitle(near, new TourApiClient.SearchPlace("1", "12", "정이품송 주차장", "", 127.8157, 36.5183))).isFalse();
        assertThat(PlaceOverviewService.prefixedTitle(near, new TourApiClient.SearchPlace("1", "12", "정이품송", "", 127.8157, 36.5183))).isFalse();
    }

    // 조회 한도·인증 오류가 나면 남은 관광지는 조회하지 않고, 시간 제한이 지나면 새 조회 없이 소개문 없이 둔다.
    @Test void stopsOnQuotaAndAfterDeadline() {
        when(tour.configured()).thenReturn(true);
        when(repo.findById(1L)).thenReturn(Optional.of(entity(temple)));
        when(repo.findById(2L)).thenReturn(Optional.of(entity(pine)));
        when(tour.searchAttractions("법주사")).thenThrow(new RecommendationException("TOUR_QUOTA", "관광정보 조회 한도에 도달했어요."));
        assertThat(service.lookup(input(), draft(), Instant.now().plusSeconds(30))).isEmpty();
        verify(tour, never()).searchAttractions("정이품송");

        clearInvocations(tour);
        assertThat(service.lookup(input(), draft(), Instant.now().minusSeconds(1))).isEmpty();
        verify(tour, never()).searchAttractions(anyString());
    }

    // 소개문은 다른 상세정보를 지우지 않고 보관하며, 찾은 소개문은 30일, 찾지 못한 기록은 7일 동안만 다시 쓴다.
    @Test void storedOverviewKeepsOtherDetailsAndExpires() {
        var place = entity(temple);
        place.updateDetails("{\"referencePrice\":{\"x\":1}}");
        var now = Instant.parse("2026-10-31T00:00:00Z");
        place.updateDetails(PlaceOverviewDetails.write(place, new PlaceOverviewDetails.Overview("125906", "소개", now), mapper));
        assertThat(place.getDetailsJson()).contains("referencePrice").contains("tourApiOverview");
        assertThat(PlaceOverviewDetails.read(place, now.plus(Duration.ofDays(29)), mapper).text()).isEqualTo("소개");
        assertThat(PlaceOverviewDetails.read(place, now.plus(Duration.ofDays(31)), mapper)).isNull();
        place.updateDetails(PlaceOverviewDetails.write(place, new PlaceOverviewDetails.Overview(null, null, now), mapper));
        assertThat(PlaceOverviewDetails.read(place, now.plus(Duration.ofDays(6)), mapper)).isNotNull();
        assertThat(PlaceOverviewDetails.read(place, now.plus(Duration.ofDays(8)), mapper)).isNull();
    }
}
