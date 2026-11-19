package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PlaceValueScoreTests {
    private static PlaceCandidate place(long id, String subtype) {
        return new PlaceCandidate(id, "장소" + id, "강원 강릉시 경포로 " + id, subtype, "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // 강릉 1박 2일, 취향 바다. 고른 관광지 1·2·3, 예비 4. AI가 1을 걷기·풍경, 2를 관람·전시로 골랐다(3·4는 활동 없음).
    private static Input input(List<Long> required, String... moods) {
        var keywords = new ArrayList<TravelKeyword>(List.of(new TravelKeyword(1L, "THEME", "바다", null)));
        for (String mood : moods) keywords.add(new TravelKeyword(2L, "MOOD", mood, null));
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", LocalDate.of(2026, 11, 14), LocalDate.of(2026, 11, 15), 2, null, 2, true, false,
            keywords, "");
        var view = new PlaceCandidateView(UUID.randomUUID(), new DestinationCandidate(99L, "강릉", "강원", "소개", "https://www.gn.go.kr/", LocalDateTime.of(2026, 9, 1, 0, 0)),
            2, true, List.of(place(1, "여행 > 관광,명소 > 해수욕장,해변"), place(2, "문화시설 > 박물관"), place(3, "여행 > 관광,명소 > 해수욕장,해변")),
            List.of(), List.of());
        var constraints = new Constraints(required, null, false, null, null);
        return new Input(view.jobId(), view, conditions, LocalTime.of(9, 0), LocalTime.of(18, 0), KakaoMapClient.Transport.CAR, 90, null, constraints,
            List.of(place(4, "문화시설 > 미술관")))
            .withPicking(new Picking(List.of(), true, List.of(), null, List.of(), Map.of(1L, "걷기·풍경", 2L, "관람·전시")));
    }

    // 순서·대표·취향·회원 기록이 모두 앞서는 1이 가장 높고, 맨 뒤 예비 관광지(4)는 회원 기록만 있어 가장 낮다.
    @Test void combinesOrderLandmarkThemeAndMembers() {
        var ranks = Map.of(1L, 0, 2L, 5, 3L, 9);  // 4는 검색 순서가 없다(UNRANKED)
        var members = Map.of(1L, new MemberStats(3, 0, 0), 4L, new MemberStats(0, 1, 0));
        var scores = PlaceValueScore.score(input(List.of()), ranks, members);
        assertThat(scores.keySet()).containsExactly(1L, 2L, 3L, 4L);
        assertThat(scores.get(1L).total()).isEqualTo(100.0);
        assertThat(scores.get(4L).order()).isZero();
        assertThat(scores.get(4L).landmark()).isZero();
        assertThat(scores.get(4L).total()).isCloseTo(10.0 / 3, within(0.01));
        // 3은 활동이 없어 분류 이름(해변)으로 바다 취향에 맞는다고 보고, 2는 AI 활동이 관람·전시라 맞지 않는다.
        assertThat(scores.get(3L).theme()).isTrue();
        assertThat(scores.get(2L).theme()).isFalse();
        assertThat(scores.values()).noneMatch(PlaceValueScore.Score::fixed);
    }

    // 필수 장소는 점수와 따로 표시만 한다(점수 자체는 바뀌지 않는다).
    @Test void marksRequiredPlacesWithoutChangingTheScore() {
        var plain = PlaceValueScore.score(input(List.of()), Map.of(), Map.of());
        var required = PlaceValueScore.score(input(List.of(4L)), Map.of(), Map.of());
        assertThat(required.get(4L).fixed()).isTrue();
        assertThat(required.get(4L).total()).isEqualTo(plain.get(4L).total());
        assertThat(required.get(4L).text()).contains("필수/중심");
    }

    // 내 기록(2026-11-09): 문화·역사를 3곳 좋아했으면 박물관(2)·미술관(4)은 만점, 바다를 1곳 좋아했으면 해변(1·3)은 1/3이다.
    // 합계는 100점에서 자르고, 내 기록이 없으면 전과 같은 점수다.
    @Test void myTasteAddsUpToTenPointsAndCapsAtHundred() {
        var ranks = Map.of(1L, 0, 2L, 5, 3L, 9);
        var members = Map.of(1L, new MemberStats(3, 0, 0), 4L, new MemberStats(0, 1, 0));
        var plain = PlaceValueScore.score(input(List.of()), ranks, members);
        var liked = PlaceValueScore.score(input(List.of()), ranks, members, Map.of("문화·역사", 3, "바다", 1));
        assertThat(liked.get(2L).myTaste()).isEqualTo(1.0);
        assertThat(liked.get(4L).total()).isCloseTo(plain.get(4L).total() + 10, within(0.01));
        assertThat(liked.get(3L).myTaste()).isCloseTo(1.0 / 3, within(0.01));
        assertThat(liked.get(1L).total()).isEqualTo(100.0);
        assertThat(PlaceValueScore.score(input(List.of()), ranks, members, Map.of())).isEqualTo(plain);
        assertThat(liked.get(2L).text()).contains("내 기록 1.00");
    }

    // 분위기(2026-11-16): 감성적인을 고르면 미술관(4)이 어울리는 종류라 10점 오르고 박물관(2)은 그대로다.
    // 한적한을 고르면 대표 관광지 가중치가 절반이라, 검색 순서 맨 앞인 해변(1)의 점수가 내려간다.
    @Test void moodAddsTenPointsAndQuietHalvesTheLandmarkWeight() {
        var ranks = Map.of(1L, 0, 2L, 5, 3L, 9);
        var plain = PlaceValueScore.score(input(List.of()), ranks, Map.of());
        var emotional = PlaceValueScore.score(input(List.of(), "감성적인"), ranks, Map.of());
        assertThat(emotional.get(4L).mood()).isTrue();
        assertThat(emotional.get(4L).total()).isCloseTo(plain.get(4L).total() + 10, within(0.01));
        assertThat(emotional.get(2L).mood()).isFalse();
        assertThat(emotional.get(2L).total()).isEqualTo(plain.get(2L).total());
        assertThat(emotional.get(4L).text()).contains("분위기 O");
        var quiet = PlaceValueScore.score(input(List.of(), "한적한"), ranks, Map.of());
        assertThat(quiet.get(1L).mood()).isFalse();
        assertThat(quiet.get(1L).total()).isCloseTo(plain.get(1L).total() - 12.5, within(0.01));
    }

    @Test void sharesAndMemberCap() {
        assertThat(PlaceValueScore.share(0, 1)).isEqualTo(1.0);
        assertThat(PlaceValueScore.share(1, 3)).isEqualTo(0.5);
        assertThat(PlaceValueScore.memberShare(null)).isZero();
        assertThat(PlaceValueScore.memberShare(new MemberStats(2, 2, 2))).isEqualTo(1.0);
    }
}
