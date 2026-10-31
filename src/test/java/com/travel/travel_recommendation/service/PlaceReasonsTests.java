package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PlaceReasonsTests {
    @Test void keepsServerReasonAndAddsAiActivityLineSeparately() {
        // 서버가 적은 '고른 이유'(실제 선택 근거 꼬리표)는 그대로 두고, AI 문장은 'AI 추천 활동' 줄로 바로 아래에 더한다.
        assertThat(PlaceReasons.note("관광·관람 · 사찰\n고른 이유: 필수 방문 · 일정의 중심", "대웅전과 팔상전을 둘러본 뒤 숲길을 따라 걸어 보세요."))
            .isEqualTo("관광·관람 · 사찰\n고른 이유: 필수 방문 · 일정의 중심\nAI 추천 활동: 대웅전과 팔상전을 둘러본 뒤 숲길을 따라 걸어 보세요.");
        // 고른 이유 뒤의 다른 줄(축제·종교 시설 안내 등)은 그 아래에 그대로 남는다.
        assertThat(PlaceReasons.note("관광·관람 · 사찰\n고른 이유: 축제 장소\n종교 시설이라 행사 중에는 관람이 제한될 수 있어요.", "경내를 천천히 걸어 보세요."))
            .isEqualTo("관광·관람 · 사찰\n고른 이유: 축제 장소\nAI 추천 활동: 경내를 천천히 걸어 보세요.\n종교 시설이라 행사 중에는 관람이 제한될 수 있어요.");
        // '고른 이유' 줄이 없으면 종류 바로 아래에 넣는다.
        assertThat(PlaceReasons.note("관광·관람 · 해변", "모래사장을 따라 걸어 보세요."))
            .isEqualTo("관광·관람 · 해변\nAI 추천 활동: 모래사장을 따라 걸어 보세요.");
    }

    @Test void changesOnlyVisitNotes() {
        var visit = new Item("VISIT", 1L, "경포해변", LocalTime.of(9, 0), LocalTime.of(10, 30), "관광·관람 · 해변", null, null, null, null);
        var meal = new Item("MEAL", 3L, "식당", LocalTime.of(12, 0), LocalTime.of(13, 0), "식사 · 한식", null, null, null, null);
        var stay = new Item("STAY", 2L, "숙소", LocalTime.of(18, 0), LocalTime.of(19, 0), "숙소", null, null, null, null);
        var plan = new Result("강릉", List.of(new Day(1, null, List.of(visit, meal, stay))), null, Instant.now(), null, null, null, null, null);
        var changed = PlaceReasons.apply(plan, Map.of(1L, "바다를 보며 걸어 보세요.", 2L, "쓰지 않는 문장", 3L, "식당 문장도 쓰지 않아요."));
        assertThat(changed.days().getFirst().items().get(0).note()).isEqualTo("관광·관람 · 해변\nAI 추천 활동: 바다를 보며 걸어 보세요.");
        // 식당·숙소 메모는 바꾸지 않는다(식당은 메뉴를 확인하지 않아 AI 문장을 붙이지 않는다).
        assertThat(changed.days().getFirst().items().get(1)).isEqualTo(meal);
        assertThat(changed.days().getFirst().items().get(2)).isEqualTo(stay);
        // 시각·장소는 그대로이고, AI 문장이 없으면 일정도 그대로다.
        assertThat(changed.days().getFirst().items().get(0).start()).isEqualTo(visit.start());
        assertThat(PlaceReasons.apply(plan, Map.of())).isSameAs(plan);
        // 결과 화면 끝 안내는 새 줄('AI 추천 활동')과 이전 초안의 줄('AI 설명') 모두에서 보여 준다.
        assertThat(PlaceReasons.used(changed)).isTrue();
        assertThat(PlaceReasons.used(plan)).isFalse();
        var old = new Item("VISIT", 1L, "경포해변", LocalTime.of(9, 0), LocalTime.of(10, 30), "관광·관람 · 해변\nAI 설명: 바다를 보며 걷기 좋아요.", null, null, null, null);
        assertThat(PlaceReasons.used(plan.withDays(List.of(new Day(1, null, List.of(old)))))).isTrue();
    }
}
