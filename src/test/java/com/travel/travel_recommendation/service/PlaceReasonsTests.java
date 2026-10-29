package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceReasonsTests {
    @Test void replacesRuleReasonsWithAiReasonButKeepsKeyFacts() {
        // 배치 규칙 설명은 AI 문장으로 바꾸고, 필수 방문·축제 안내는 뒤에 남긴다.
        assertThat(PlaceReasons.note("관광·관람 · 해변\n고른 이유: 필수 방문 장소로 고른 곳이에요. 하루 시간이 남아 더 넣은 곳이에요.",
            "가을 바다를 보며 걷기 좋은 해변이에요."))
            .isEqualTo("관광·관람 · 해변\n고른 이유: 가을 바다를 보며 걷기 좋은 해변이에요. 필수 방문 장소로 고른 곳이에요.");
        assertThat(PlaceReasons.note("관광·관람 · 공원\n고른 이유: 여행 기간에 이곳이나 바로 근처에서 커피축제(10.9(금)) 행사가 열려요. "
            + "정확한 행사 장소와 일정은 출발 전에 확인해 주세요. 이 날 일정의 중심으로 고른 곳이라 첫 방문지로 넣었어요.", "산책하기 좋은 공원이에요."))
            .isEqualTo("관광·관람 · 공원\n고른 이유: 산책하기 좋은 공원이에요. 여행 기간에 이곳이나 바로 근처에서 커피축제(10.9(금)) 행사가 열려요. "
                + "정확한 행사 장소와 일정은 출발 전에 확인해 주세요.");
        // 관광공사 연관 관광지로 확인한 인기 음식점 근거도 AI 문장 뒤에 남긴다.
        assertThat(PlaceReasons.note("식사 · 한식\n고른 이유: 한국관광공사 연관 관광지 정보에서 이 지역 여행자들이 함께 많이 찾은 음식점이에요. "
            + "앞서 먹은 음식(국수)과 다른 분류예요.", "꼬막비빔밥을 맛볼 수 있는 곳이에요."))
            .isEqualTo("식사 · 한식\n고른 이유: 꼬막비빔밥을 맛볼 수 있는 곳이에요. 한국관광공사 연관 관광지 정보에서 이 지역 여행자들이 함께 많이 찾은 음식점이에요.");
        // '고른 이유' 줄이 없으면 종류 바로 아래에 넣고 위치 같은 다른 줄은 그대로 둔다.
        assertThat(PlaceReasons.note("식사 · 한식\n위치: 경포해변에서 직선 약 300m예요.", "걷고 난 뒤 든든하게 먹기 좋은 한식집이에요."))
            .isEqualTo("식사 · 한식\n고른 이유: 걷고 난 뒤 든든하게 먹기 좋은 한식집이에요.\n위치: 경포해변에서 직선 약 300m예요.");
    }

    @Test void changesOnlyVisitAndMealNotes() {
        var visit = new Item("VISIT", 1L, "경포해변", LocalTime.of(9, 0), LocalTime.of(10, 30), "관광·관람 · 해변", null, null, null, null);
        var stay = new Item("STAY", 2L, "숙소", LocalTime.of(18, 0), LocalTime.of(19, 0), "숙소", null, null, null, null);
        var plan = new Result("강릉", List.of(new Day(1, null, List.of(visit, stay))), null, Instant.now(), null, null, null, null, null);
        var changed = PlaceReasons.apply(plan, Map.of(1L, "바다를 보며 걷기 좋아요.", 2L, "쓰지 않는 이유"));
        assertThat(changed.days().getFirst().items().get(0).note()).isEqualTo("관광·관람 · 해변\n고른 이유: 바다를 보며 걷기 좋아요.");
        assertThat(changed.days().getFirst().items().get(1)).isEqualTo(stay);
        // 시각·장소는 그대로이고, AI 이유가 없으면 일정도 그대로다.
        assertThat(changed.days().getFirst().items().get(0).start()).isEqualTo(visit.start());
        assertThat(PlaceReasons.apply(plan, Map.of())).isSameAs(plan);
    }
}
