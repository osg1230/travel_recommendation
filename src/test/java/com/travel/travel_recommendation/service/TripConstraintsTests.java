package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.*;
import java.util.*;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TripConstraintsTests {
    private static final LocalTime NINE = LocalTime.of(9, 0), SIX = LocalTime.of(18, 0);
    private static List<PlaceCandidate> pool(int size) {
        return LongStream.rangeClosed(1, size).mapToObj(id -> new PlaceCandidate(id, "장소" + id, "강원 강릉시 경포로 " + id,
            "관광", "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0))).toList();
    }
    private static List<Long> ids(List<PlaceCandidate> places) { return places.stream().map(PlaceCandidate::placeId).toList(); }
    private static Constraints c(List<Long> required, Integer daily, boolean dailyRequired) {
        return new Constraints(required, daily, dailyRequired, null, List.of());
    }

    @Test void withoutDailyCountSelectsWhatFitsInTheDay() {
        // 09:00~18:00, 체류 90분이면 점심을 빼고 하루 약 3곳이다.
        assertThat(TripConstraints.estimatedDailyCapacity(NINE, SIX, 90)).isEqualTo(3);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, null))).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(), null, false)))).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        // 체류시간이 짧거나 하루가 길면 더 많이 고르지만 하루 4곳을 넘기지 않고, 저녁까지 이어지는 날은 저녁 식사 시간도 뺀다.
        assertThat(TripConstraints.estimatedDailyCapacity(NINE, SIX, 60)).isEqualTo(4);
        assertThat(TripConstraints.estimatedDailyCapacity(LocalTime.of(8, 0), LocalTime.of(21, 0), 30)).isEqualTo(4);
        assertThat(TripConstraints.estimatedDailyCapacity(NINE, LocalTime.of(21, 0), 90)).isEqualTo(4);
        // 후보가 16곳뿐이면 7일이라도 16곳까지만 고른다(전체 최대는 21곳).
        assertThat(ids(TripConstraints.select(pool(16), 7, NINE, SIX, 90, null))).hasSize(16);
    }

    @Test void requiredPlacesComeFirstAndDailyCountSetsTotal() {
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(9L, 12L), null, false)))).containsExactly(9L, 12L, 1L, 2L, 3L, 4L);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(9L), 3, true)))).containsExactly(9L, 1L, 2L, 3L, 4L, 5L);
        assertThat(ids(TripConstraints.select(pool(16), 3, NINE, SIX, 90, c(List.of(), 1, true)))).hasSize(3);
    }

    @Test void preferredDailyCountIsReducedToWhatFitsInsteadOfFailing() {
        // 09:00~13:00, 체류 90분이면 하루 약 1곳이다. 선호 조건은 줄여서 반영한다.
        assertThat(TripConstraints.estimatedDailyCapacity(NINE, LocalTime.of(13, 0), 90)).isEqualTo(1);
        assertThat(ids(TripConstraints.select(pool(16), 2, NINE, LocalTime.of(13, 0), 90, c(List.of(), 4, false)))).hasSize(2);
        // 후보가 적으면 있는 만큼만 쓴다.
        assertThat(ids(TripConstraints.select(pool(3), 2, NINE, SIX, 60, c(List.of(), 3, false)))).hasSize(3);
    }

    @Test void contradictoryRequiredConditionsAreReportedNotRelaxed() {
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 2, NINE, SIX, 90, c(List.of(1L, 2L, 3L), 1, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e -> {
                assertThat(e.conflicts()).singleElement().satisfies(conflict -> {
                    assertThat(conflict.reason()).contains("필수 방문 장소 3곳을 하루 1곳씩 2일");
                    assertThat(conflict.choices()).contains("하루 방문 수를 '가능하면 반영'으로 바꾸기");
                });
            });
        assertThatThrownBy(() -> TripConstraints.select(pool(16), 1, NINE, LocalTime.of(13, 0), 90, c(List.of(), 3, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("하루 약 1곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(3), 2, NINE, SIX, 60, c(List.of(), 2, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("후보가 3곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(27), 7, NINE, SIX, 60, c(List.of(), 4, true)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("전체 최대 21곳")));
        assertThatThrownBy(() -> TripConstraints.select(pool(22), 7, NINE, SIX, 60, c(LongStream.rangeClosed(1, 22).boxed().toList(), null, false)))
            .isInstanceOfSatisfying(TripConstraints.ConflictException.class, e ->
                assertThat(e.conflicts()).anySatisfy(conflict -> assertThat(conflict.reason()).contains("최대 21곳")));
    }

    // 관광지 후보는 1~3일이면 16곳, 길어지면 하루 4곳씩(최대 21곳)에 예비 6곳을 더한다.
    @Test void candidateCountGrowsWithTripLength() {
        assertThat(TripConstraints.candidateLimit(null)).isEqualTo(16);
        assertThat(TripConstraints.candidateLimit(2)).isEqualTo(16);
        assertThat(TripConstraints.candidateLimit(4)).isEqualTo(22);
        assertThat(TripConstraints.candidateLimit(5)).isEqualTo(26);
        assertThat(TripConstraints.candidateLimit(7)).isEqualTo(27);
    }

    // 저녁 관광 후보: 전망대·해변·테마거리처럼 밤에 걷거나 야경을 보는 곳만 고르고, 휴양림·사찰은 고르지 않는다.
    @Test void eveningSpotsAreOutdoorWalkingOrNightViewPlaces() {
        assertThat(TripConstraints.eveningSpot(kind(1, "오동도", "여행 > 관광,명소 > 전망대"))).isTrue();
        assertThat(TripConstraints.eveningSpot(kind(2, "만성리검은모래해변", "여행 > 관광,명소 > 해수욕장,해변"))).isTrue();
        assertThat(TripConstraints.eveningSpot(kind(3, "여수낭만포차거리", "여행 > 관광,명소"))).isTrue();
        assertThat(TripConstraints.eveningSpot(kind(4, "봉황산자연휴양림", "여행 > 관광,명소 > 자연휴양림"))).isFalse();
        assertThat(TripConstraints.eveningSpot(kind(5, "향일암", "문화,예술 > 종교 > 불교 > 절,사찰"))).isFalse();
        var tower = kind(6, "스카이타워", "여행 > 관광,명소 > 전망대");
        var forest = kind(7, "휴양림", "여행 > 관광,명소 > 자연휴양림");
        var chosen = kind(8, "해변", "여행 > 관광,명소 > 해수욕장,해변");
        assertThat(TripConstraints.eveningPlaces(List.of(chosen, forest, tower), List.of(chosen.placeId()), 3)).containsExactly(tower);
        // 16곳 후보로 2박 3일(하루 3곳 → 낮 9곳)이면 숙박 2박 + 여유 2곳 = 4곳을 저녁 몫으로 떼어 두고, 후보가 모자라면 떼어 두지 않는다.
        assertThat(TripConstraints.eveningShare(16, 3, 3)).isEqualTo(4);
        assertThat(TripConstraints.eveningShare(26, 5, 3)).isEqualTo(6);
        assertThat(TripConstraints.eveningShare(8, 3, 3)).isZero();
    }
    private static PlaceCandidate kind(long id, String name, String subtype) {
        return new PlaceCandidate(id, name, "전남 여수시 도로 " + id, subtype, "", "https://place.map.kakao.com/" + id, "",
            LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // 여수 실제 좌표(카카오 2026-09-22). 이름순이면 거문도·금오도가 먼저지만, 모여 있는 곳부터 고르고 먼 섬은 뒤로 보낸다.
    @Test void placesNearTheCenterComeFirstAndFarIslandsGoLast() {
        var geomundo = located(1, "거문도", 34.026897, 127.316168);
        var geumodo = located(2, "금오도", 34.522693, 127.750414);
        var odongdo = located(3, "오동도", 34.744598, 127.766289);
        var manseong = located(4, "만성리검은모래해변", 34.778857, 127.745476);
        var jangdo = located(5, "장도", 34.743379, 127.664362);
        var noCoordinate = pool(6).getLast();
        var ordered = TripConstraints.nearCenterFirst(List.of(geomundo, geumodo, noCoordinate, odongdo, manseong, jangdo));
        assertThat(ids(ordered).subList(0, 3)).containsExactlyInAnyOrder(3L, 4L, 5L);
        assertThat(ids(ordered)).containsSubsequence(2L, 1L, 6L).endsWith(1L, 6L);
        // 필수 장소는 멀어도 먼저 넣는다.
        assertThat(ids(TripConstraints.select(ordered, 1, NINE, SIX, 90, c(List.of(1L), null, false)))).startsWith(1L);
    }
    // 거제 실제 분류(카카오). 해수욕장이 몰려 있어도 종류를 돌아가며 고른다.
    @Test void kindsAreMixedSoBeachesDoNotCrowdOutOtherPlaces() {
        var beach1 = typed(1, "구조라해수욕장", "여행 > 관광,명소 > 해수욕장,해변");
        var beach2 = typed(2, "와현모래숲해수욕장", "여행 > 관광,명소 > 해수욕장,해변");
        var view = typed(3, "신선대전망대", "여행 > 관광,명소 > 전망대");
        var beach3 = typed(4, "해변 테스트", "해변");
        var park = typed(5, "거제맹종죽테마공원", "여행 > 관광,명소 > 테마파크");
        assertThat(TripConstraints.kind(beach1)).isEqualTo("해변");
        assertThat(TripConstraints.kind(beach3)).isEqualTo("해변");
        assertThat(TripConstraints.kind(view)).isEqualTo("전망대");
        assertThat(ids(TripConstraints.mixKinds(List.of(beach1, beach2, view, beach3, park)))).containsExactly(1L, 3L, 5L, 2L, 4L);
        // 하루 1곳씩 3일이면 해변·전망대·테마파크를 고른다(예전에는 해변 2곳이 먼저 뽑혔다).
        assertThat(ids(TripConstraints.select(TripConstraints.mixKinds(List.of(beach1, beach2, view, beach3, park)), 3, NINE, SIX, 90,
            c(List.of(), 1, true)))).containsExactly(1L, 3L, 5L);
    }
    private static PlaceCandidate typed(long id, String name, String subtype) {
        return new PlaceCandidate(id, name, "경상남도 거제시 " + id, subtype, "", "https://place.map.kakao.com/" + id, "",
            LocalDateTime.of(2026, 9, 22, 0, 0));
    }
    // 경주 실제 좌표(카카오 2026-10-05). 천마총은 대릉원 안(약 250m)이라 한 곳만 남기고, 먼 문무대왕릉과 넓은 분류는 그대로 둔다.
    @Test void sameKindPlacesWithinWalkingDistanceCountAsOneSpot() {
        var daereungwon = located(1, "대릉원", "여행 > 관광,명소 > 문화유적 > 릉,묘,총", 35.838191, 129.213334);
        var cheonmachong = located(2, "천마총", "여행 > 관광,명소 > 문화유적 > 릉,묘,총", 35.838457, 129.210559);
        var munmu = located(3, "경주문무대왕릉", "여행 > 관광,명소 > 문화유적 > 릉,묘,총", 35.738262, 129.486877);
        var woljeong = located(4, "월정교", "여행 > 관광,명소 > 문화유적", 35.829300, 129.218121);
        var cheomseongdae = located(5, "첨성대", "여행 > 관광,명소 > 문화유적", 35.829400, 129.218200);
        var ordered = List.of(daereungwon, woljeong, cheonmachong, munmu, cheomseongdae);
        assertThat(TripConstraints.withoutSameSpot(ordered, List.of()))
            .containsExactly(daereungwon, woljeong, munmu, cheomseongdae);
        // 사용자가 필수로 고른 곳은 가까워도 빼지 않는다.
        assertThat(TripConstraints.withoutSameSpot(ordered, List.of(2L))).containsExactlyElementsOf(ordered);
    }
    private static PlaceCandidate located(long id, String name, String subtype, double lat, double lon) {
        return new PlaceCandidate(id, name, "경북 경주시 " + id, subtype, "", "https://place.map.kakao.com/" + id, "",
            LocalDateTime.of(2026, 10, 5, 0, 0), lon, lat, "KAKAO", null);
    }

    private static PlaceCandidate located(long id, String name, double lat, double lon) {
        return new PlaceCandidate(id, name, "전남광주통합특별시 여수시 " + id, "관광", "", "https://place.map.kakao.com/" + id, "",
            LocalDateTime.of(2026, 9, 22, 0, 0), lon, lat, "KAKAO", null);
    }

    @Test void rejectsForgedPlacesAndValues() {
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(99L), null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(1L, 1L), null, false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TripConstraints.select(pool(4), 1, NINE, SIX, 90, c(List.of(), 11, false))).isInstanceOf(IllegalArgumentException.class);
    }

    // AI 순서: 중심 장소 → AI가 고른 순서 → AI가 뺄 곳. 같은 종류가 두 번 이어진 뒤에는 다른 종류를 먼저 두고, 후보에 없는 ID는 무시한다.
    @Test void aiPicksOrderPlacesAndOnlyBreakLongRunsOfOneKind() {
        var pool = List.of(kind(1, "해변A", "여행 > 관광,명소 > 해수욕장,해변"), kind(2, "해변B", "여행 > 관광,명소 > 해수욕장,해변"),
            kind(3, "해변C", "여행 > 관광,명소 > 해수욕장,해변"), kind(4, "박물관", "문화,예술 > 문화시설 > 박물관"),
            kind(5, "동네절", "문화,예술 > 종교 > 불교 > 절,사찰"), kind(6, "공원", "여행 > 관광,명소 > 도시근린공원"));
        assertThat(ids(TripConstraints.byPicks(pool, List.of(4L), List.of(1L, 2L, 3L, 99L, 6L), List.of(5L)))).containsExactly(4L, 1L, 2L, 6L, 3L, 5L);
        // AI가 언급하지 않은 곳(6)은 고른 곳 뒤, 뺄 곳 앞에 둔다.
        assertThat(ids(TripConstraints.byPicks(pool, List.of(), List.of(4L, 1L), List.of(5L)))).containsExactly(4L, 1L, 2L, 6L, 3L, 5L);
        // 분류가 '관광,명소'로만 된 곳은 종류를 알 수 없어 이어져도 순서를 바꾸지 않는다.
        var plain = List.of(kind(1, "가", "여행 > 관광,명소"), kind(2, "나", "여행 > 관광,명소"), kind(3, "다", "여행 > 관광,명소"), kind(4, "라", "여행 > 관광,명소 > 호수"));
        assertThat(ids(TripConstraints.limitKindRuns(plain))).containsExactly(1L, 2L, 3L, 4L);
    }

    // 2026-11-03 영덕 초안: 해수욕장·해안길·숲은 분류가 달라 이어져도 몰랐다. AI가 고른 활동(걷기·풍경)으로 세면 다른 활동을 먼저 당겨 온다.
    @Test void aiActivitiesLimitRunsAcrossDifferentKinds() {
        var pool = List.of(kind(1, "고래불해수욕장", "여행 > 관광,명소 > 해수욕장,해변"), kind(2, "영덕블루로드", "여행 > 관광,명소 > 둘레길"),
            kind(3, "벌영리메타세콰이어숲", "여행 > 관광,명소 > 숲"), kind(4, "괴시리전통마을", "여행 > 관광,명소 > 전통마을"));
        // 분류만 보면 모두 다른 종류라 AI 순서 그대로다.
        assertThat(ids(TripConstraints.byPicks(pool, List.of(), List.of(1L, 2L, 3L, 4L), List.of()))).containsExactly(1L, 2L, 3L, 4L);
        var activities = Map.of(1L, "걷기·풍경", 2L, "걷기·풍경", 3L, "걷기·풍경", 4L, "역사·문화");
        assertThat(ids(TripConstraints.byPicks(pool, List.of(), List.of(1L, 2L, 3L, 4L), List.of(), activities))).containsExactly(1L, 2L, 4L, 3L);
    }

    // AI 순서로 고를 때는 앞쪽(낮에 갈 만한 곳)을 저녁 관광 몫으로 떼지 않고, AI가 뺄 곳은 예비·저녁 관광으로 쓰지 않는다.
    @Test void aiOrderKeepsTopPicksForDaytime() {
        var ordered = new ArrayList<PlaceCandidate>();
        for (long id = 1; id <= 10; id++)
            ordered.add(kind(id, "장소" + id, id == 1 || id >= 8 ? "여행 > 관광,명소 > 전망대" : "여행 > 관광,명소 > 문화유적"));
        // 2일·09:00~18:00·체류 90분이면 낮 관광지는 6곳이다.
        var ai = ItineraryJobService.arrange(ordered, Set.of(10L), true, true, 2, NINE, SIX, 90, null);
        assertThat(ids(ai.selected())).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(ids(ai.evening())).containsExactly(8L, 9L);
        assertThat(ids(ai.reserve())).containsExactly(7L);
        // 서버 규칙 순서일 때는 지금처럼 앞에서부터 저녁 관광 몫을 뗀다.
        var rule = ItineraryJobService.arrange(ordered, Set.of(), false, true, 2, NINE, SIX, 90, null);
        assertThat(ids(rule.evening())).containsExactly(1L, 8L, 9L);
        assertThat(ids(rule.selected())).containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
    }
    // AI가 밤 명소로 꼽은 곳은 숙박하는 밤 수만큼 저녁 관광 후보 맨 앞에 두고 낮 관광지에서 뺀다.
    // 저녁 관광 분류가 아닌 곳(문화유적)과 중심 장소는 밤 명소로 쓰지 않고, 당일 여행은 저녁 관광이 없어 그대로다.
    @Test void aiNightSpotsGoToTheFrontOfEveningCandidates() {
        var ordered = new ArrayList<PlaceCandidate>();
        for (long id = 1; id <= 10; id++)
            ordered.add(kind(id, "장소" + id, id == 1 || id >= 8 ? "여행 > 관광,명소 > 전망대" : "여행 > 관광,명소 > 문화유적"));
        var night = ItineraryJobService.arrange(ordered, Set.of(10L), List.of(2L, 1L), true, true, 2, NINE, SIX, 90, null);
        assertThat(ids(night.evening())).containsExactly(1L, 8L, 9L);
        assertThat(ids(night.selected())).containsExactly(2L, 3L, 4L, 5L, 6L, 7L);
        // 2박 3일이면 두 곳까지 둔다(후보 16곳). 중심 장소(8)는 밤 명소로 꼽혀도 낮 일정에 남긴다.
        var many = new ArrayList<>(ordered);
        for (long id = 11; id <= 16; id++) many.add(kind(id, "장소" + id, "여행 > 관광,명소 > 문화유적"));
        var center = new Constraints(List.of(), null, false, null, List.of(), null, List.of(8L));
        assertThat(ids(ItineraryJobService.arrange(many, Set.of(), List.of(1L, 8L, 9L), true, true, 3, NINE, SIX, 90, center).evening()))
            .startsWith(1L, 9L).doesNotContain(8L);
        assertThat(ItineraryJobService.arrange(ordered, Set.of(), List.of(1L), true, false, 1, NINE, SIX, 90, null).evening()).isEmpty();
    }
    // 다시 만들기: 직전 초안의 관광지는 뒤로 가되 빠지지 않는다. 지킬 장소는 제자리, AI가 뺄 곳은 그보다 더 뒤에 둔다.
    @Test void previousPlacesMoveBehindOthersButBeforeSkipped() {
        var ordered = pool(6);
        assertThat(ids(TripConstraints.previousLast(ordered, List.of(1L, 2L, 3L), List.of(2L), List.of(6L)))).containsExactly(2L, 4L, 5L, 1L, 3L, 6L);
        assertThat(TripConstraints.previousLast(ordered, List.of(), List.of(), List.of())).isSameAs(ordered);
    }
    @Test void kindFromSubtypeMatchesCandidateKind() {
        assertThat(TripConstraints.kind("여행 > 관광,명소 > 해수욕장,해변")).isEqualTo("해변");
        assertThat(TripConstraints.kind("가정,생활 > 시장")).isEqualTo("시장");
        assertThat(TripConstraints.kind((String) null)).isEmpty();
    }
}
