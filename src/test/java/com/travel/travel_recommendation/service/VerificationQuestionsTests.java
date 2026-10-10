package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.Place;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class VerificationQuestionsTests {
    private static PlaceCandidate place(long id, String name) {
        return new PlaceCandidate(id, name, "강원특별자치도 강릉시 창해로 " + id, "", "", "https://www.gn.go.kr", "", LocalDateTime.now());
    }
    private static Input input(String original, String purpose, boolean suggested, PurposeSearch search) {
        var conditions = new ConfirmedTravelConditions("TEXT", original, "강릉", null, null, 1, null, 2, false, null, List.of(), "", true, purpose, search, suggested, List.of());
        var places = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(9L, "강릉", "강원특별자치도 강릉시", "", "", LocalDateTime.now()),
            1, false, List.of(place(1, "경포해변"), place(2, "아쿠아리움")), List.of(), List.of(place(3, "바다식당")));
        return new Input(places.jobId(), places, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90);
    }
    private static Result draft() {
        var items = List.of(new Item("VISIT", 1L, "경포해변", LocalTime.of(9, 0), LocalTime.of(10, 30), "", "", "", null, null),
            new Item("TRANSIT", 2L, "경포해변 → 아쿠아리움", LocalTime.of(10, 30), LocalTime.of(10, 50), "", "", "", 600, null),
            new Item("VISIT", 2L, "아쿠아리움", LocalTime.of(10, 50), LocalTime.of(12, 20), "", "", "", null, null),
            new Item("MEAL", 3L, "바다식당", LocalTime.of(12, 30), LocalTime.of(13, 30), "", "", "", null, null));
        return new Result("강릉", List.of(new Day(1, null, List.of(items.toArray(new Item[0])))), KakaoMapClient.Transport.CAR, Instant.now());
    }

    // 반려동물 동반 여행이면 관광공사 정보가 없는 장소만 묻고, 정보가 있는 장소는 묻지 않는다.
    @Test void asksPetQuestionOnlyForPlacesWithoutTourApiInfo() {
        var input = input("강아지랑 가요", "", false, null);
        var known = new PetCheck(1L, "경포해변", new PlacePetInfo("1", "전구역 동반가능", "", "", "", Instant.now()));
        var questions = VerificationQuestions.of(input, draft().withPets(List.of(known, new PetCheck(2L, "아쿠아리움", null))));
        assertThat(questions).extracting(CheckQuestion::placeId).containsExactly(2L, 3L);
        assertThat(questions).allMatch(q -> "PET".equals(q.topic()));
        assertThat(questions.get(0).question()).contains("아쿠아리움", "반려동물");
        // 동반 정보를 아예 조회하지 않았으면 모든 장소를 묻는다.
        assertThat(VerificationQuestions.of(input, draft())).hasSize(3);
    }

    // 사용자가 직접 말한 목적은 목적 시설이 일정에 있으면 그곳만, 없으면 관광지 전체를 묻는다. 서비스가 제안한 방향은 묻지 않는다.
    @Test void asksPurposeQuestionForPurposeFacilitiesOrAllAttractions() {
        var facility = new PurposeSearch.PurposePlace(2L, 9L, "강릉", "아쿠아리움", "주소", "문화시설", "");
        var search = new PurposeSearch("FOUND", List.of("아쿠아리움"), List.of(facility), "", Instant.now(), List.of());
        var focused = VerificationQuestions.of(input("", "상어 관람", false, search), draft());
        assertThat(focused).singleElement().satisfies(q -> {
            assertThat(q.placeId()).isEqualTo(2L); assertThat(q.topic()).isEqualTo("PURPOSE"); assertThat(q.question()).contains("상어 관람");
        });
        assertThat(VerificationQuestions.of(input("", "상어 관람", false, null), draft())).extracting(CheckQuestion::placeId).containsExactly(1L, 2L);
        assertThat(VerificationQuestions.of(input("", "가을 바다 걷기", true, null), draft())).isEmpty();
        assertThat(VerificationQuestions.of(input("", "", false, null), draft())).isEmpty();
        // 반려동물과 활동이 섞인 목적("강아지와 상어 관람")은 활동 질문을 먼저, 반려동물 질문을 뒤에 만든다(상한에 걸려도 활동 질문은 남는다).
        var mixed = VerificationQuestions.of(input("강아지랑", "강아지와 함께 상어 관람", false, search), draft());
        assertThat(mixed).extracting(CheckQuestion::topic).containsExactly("PURPOSE", "PET", "PET", "PET");
    }

    // 장소 상세정보에 주제별로 보관하고, 같은 질문·7일 안에서만 재사용하며, 못 찾은 답은 24시간만 쓴다.
    @Test void detailsStoreAnswersPerTopicAndReuseOnlySameQuestion() {
        var mapper = new JsonMapper();
        var entity = Place.fromKakao(new KakaoPlace("k1", "ATTRACTION", "경포해변", "강원특별자치도 강릉시", "강원특별자치도 강릉시 창해로 1", "", 128.9, 37.8, "https://place.map.kakao.com/1", LocalDateTime.now()));
        entity.updateDetails("{\"foods\":[\"기존\"]}");
        var pet = new CheckAnswer(1L, "경포해변", "PET", "q-pet", "YES", "가능해요", "https://a.kr/", "VERIFIED", Instant.now().minus(Duration.ofDays(2)));
        var purpose = new CheckAnswer(1L, "경포해변", "PURPOSE", "q-purpose", "UNKNOWN", "", null, "NOT_FOUND", Instant.now().minus(Duration.ofHours(2)));
        entity.updateDetails(PlaceCheckDetails.write(entity, pet, mapper));
        entity.updateDetails(PlaceCheckDetails.write(entity, purpose, mapper));
        assertThat(mapper.readTree(entity.getDetailsJson()).path("foods").get(0).asText()).isEqualTo("기존");
        var now = Instant.now();
        assertThat(PlaceCheckDetails.read(entity, "PET", "q-pet", now, mapper)).isEqualTo(pet);
        assertThat(PlaceCheckDetails.read(entity, "PET", "다른 질문", now, mapper)).isNull();
        assertThat(PlaceCheckDetails.read(entity, "PURPOSE", "q-purpose", now, mapper)).isEqualTo(purpose);
        assertThat(PlaceCheckDetails.read(entity, "PURPOSE", "q-purpose", now.plus(Duration.ofDays(1)), mapper)).isNull();
        assertThat(PlaceCheckDetails.read(entity, "PET", "q-pet", now.plus(Duration.ofDays(6)), mapper)).isNull();
    }
}
