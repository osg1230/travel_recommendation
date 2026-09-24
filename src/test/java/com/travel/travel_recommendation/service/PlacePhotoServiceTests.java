package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.time.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class PlacePhotoServiceTests {
    private TourApiClient.SearchPlace found(String image, String copyright) {
        return new TourApiClient.SearchPlace("129784", "14", "오죽헌", "강원특별자치도 강릉시 율곡로3139번길 24", 128.8796, 37.7791, image, copyright);
    }

    @Test void usesOnlyTourApiImagesThatNeedOnlyAttribution() {
        var photo = PlacePhotoService.usable(found("http://tong.visitkorea.or.kr/cms/resource/1/1_image2_1.jpg", "Type1"));
        assertThat(photo.url()).isEqualTo("https://tong.visitkorea.or.kr/cms/resource/1/1_image2_1.jpg");
        assertThat(PlacePhotoService.usable(found("https://tong.visitkorea.or.kr/a.jpg", "Type3"))).isNotNull();
        // 출처 표시 외 조건이 있는 유형, 사진 없음, 관광공사 서버가 아닌 주소는 쓰지 않는다.
        assertThat(PlacePhotoService.usable(found("https://tong.visitkorea.or.kr/a.jpg", "Type2"))).isNull();
        assertThat(PlacePhotoService.usable(found("", "Type1"))).isNull();
        assertThat(PlacePhotoService.usable(found("https://example.com/a.jpg", "Type1"))).isNull();
        assertThat(PlacePhotoService.usable(found("https://tong.visitkorea.or.kr/a.jpg\" onerror=\"x", "Type1"))).isNull();
    }

    // 실제 관광공사 제목 예: "경주 불국사 [유네스코 세계유산]", "경주 첨성대"(카카오 주소는 지번이라 좌표로 비교).
    @Test void matchesTourApiTitleWithCityAndNoteByNameAndLocation() {
        var bulguksa = new com.travel.travel_recommendation.dto.PlaceCandidate(1L, "불국사", "경북 경주시 불국로 385", "", "", "", "",
            LocalDateTime.now(), 129.3320, 35.7900, "KAKAO");
        var near = new TourApiClient.SearchPlace("1", "12", "경주 불국사 [유네스코 세계유산]", "경상북도 경주시 불국로 385 (진현동)", 129.3322, 35.7902, "x", "Type1");
        var seoul = new TourApiClient.SearchPlace("2", "12", "불국사(서울)", "서울특별시 강남구 광평로10길 30-71", 127.08, 37.49, "x", "Type1");
        var restaurant = new TourApiClient.SearchPlace("3", "39", "불국사밥면", "경상북도 경주시 불국장터길 29", 129.33, 35.79, "x", "Type3");
        assertThat(PlacePhotoService.samePlace(bulguksa, "경주", near)).isTrue();
        assertThat(PlacePhotoService.samePlace(bulguksa, "경주", seoul)).isFalse();
        assertThat(PlacePhotoService.samePlace(bulguksa, "경주", restaurant)).isFalse();
        // 좌표가 없는 장소는 도로명 주소가 같아야 한다.
        var noCoordinates = new com.travel.travel_recommendation.dto.PlaceCandidate(2L, "불국사", "경상북도 경주시 불국로 385", "", "", "", "",
            LocalDateTime.now());
        assertThat(PlacePhotoService.samePlace(noCoordinates, "경주", near)).isTrue();
        assertThat(PlacePhotoService.samePlace(noCoordinates, "경주",
            new TourApiClient.SearchPlace("4", "12", "불국사", "경상북도 경주시 다른로 1", 129.3, 35.8, "x", "Type1"))).isFalse();
    }

    @Test void storedPhotoKeepsOtherDetailsAndExpiresOrFollowsPlaceChanges() {
        var mapper = new JsonMapper();
        var place = mock(Place.class);
        when(place.getName()).thenReturn("오죽헌"); when(place.getAddress()).thenReturn("강릉시 율곡로3139번길 24");
        when(place.getDetailsJson()).thenReturn("{\"referencePrice\":{\"x\":1}}");
        var now = Instant.parse("2026-09-24T00:00:00Z");
        String json = PlacePhotoDetails.write(place, new PlacePhotoDetails.Photo("https://tong.visitkorea.or.kr/a.jpg", "Type1", now), mapper);
        assertThat(json).contains("referencePrice").contains("tourApiPhoto");

        when(place.getDetailsJson()).thenReturn(json);
        assertThat(PlacePhotoDetails.read(place, now.plus(Duration.ofDays(29)), mapper).found()).isTrue();
        assertThat(PlacePhotoDetails.read(place, now.plus(Duration.ofDays(31)), mapper)).isNull();
        when(place.getName()).thenReturn("다른 이름");
        assertThat(PlacePhotoDetails.read(place, now.plusSeconds(60), mapper)).isNull();
    }
}
