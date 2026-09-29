package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.service.PreferenceDiscovery.Preference;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PreferenceDiscoveryTests {
    /** 질문 ID, 답을 번갈아 받아 묻는 순서대로 담는다. */
    private static LinkedHashMap<String, String> answers(String... pairs) {
        var answers = new LinkedHashMap<String, String>();
        for (int i = 0; i < pairs.length; i += 2) answers.put(pairs[i], pairs[i + 1]);
        return answers;
    }

    @Test void followUpQuestionsDependOnChosenAnswer() {
        // 여름을 고르면 여름 질문, 겨울을 고르면 겨울 질문이 이어진다.
        assertThat(PreferenceDiscovery.nextQuestion(answers("SEASON", "A")).id()).isEqualTo("SUMMER_DAY");
        assertThat(PreferenceDiscovery.nextQuestion(answers("SEASON", "B")).id()).isEqualTo("WINTER_DAY");
        // 가장 최근 답의 이어지는 질문이 먼저이고, 그다음 앞 답의 남은 질문을 묻는다.
        assertThat(PreferenceDiscovery.nextQuestion(answers("SEASON", "A", "SUMMER_DAY", "A")).id()).isEqualTo("SEA_TIME");
        assertThat(PreferenceDiscovery.nextQuestion(answers("SEASON", "A", "SUMMER_DAY", "A", "SEA_TIME", "B", "AFTERNOON", "A")).id())
            .isEqualTo("NIGHT");
        // 상관없음·건너뛰기는 이어지는 질문 없이 다른 시작 질문으로 넘어간다.
        assertThat(PreferenceDiscovery.nextQuestion(answers("SEASON", "SKIP")).id()).isEqualTo("PHOTO");
        assertThat(PreferenceDiscovery.nextQuestion(answers("PHOTO", "ANY")).id()).isEqualTo("SEASON");
    }

    @Test void asksAtMostFiveQuestions() {
        var full = answers("SEASON", "A", "SUMMER_DAY", "A", "SEA_TIME", "B", "AFTERNOON", "A", "NIGHT", "B");
        assertThat(PreferenceDiscovery.nextQuestion(full)).isNull();
        var result = PreferenceDiscovery.evaluate(full);
        assertThat(result.answered()).isEqualTo(5);
        assertThat(result.skipped()).isZero();
    }

    @Test void scoresTagsOfChosenScenesAndExplainsWhy() {
        var result = PreferenceDiscovery.evaluate(answers("SEASON", "A", "SUMMER_DAY", "A", "SEA_TIME", "B"));
        assertThat(result.answered()).isEqualTo(3);
        // 묻지 않은 질문도 건너뛴 것으로 센다.
        assertThat(result.skipped()).isEqualTo(2);
        assertThat(result.preferences()).extracting(Preference::tag, Preference::score)
            .containsExactly(tuple("SEA", 3), tuple("ACTIVITY", 2), tuple("REST", 1));
        assertThat(result.preferences().getFirst().reasons()).containsExactly(
            "여름, 시원한 물가에서 신나게 놀기", "해수욕장에서 물놀이나 서핑하기", "바다가 보이는 카페에서 느긋하게 쉬기");
    }

    @Test void sameAnswersGiveSameResultAndChangedAnswersAreRecalculated() {
        var answers = answers("PHOTO", "B", "MUST_EAT", "A");
        assertThat(PreferenceDiscovery.evaluate(answers)).isEqualTo(PreferenceDiscovery.evaluate(new LinkedHashMap<>(answers)));
        assertThat(PreferenceDiscovery.evaluate(answers).preferences()).extracting(Preference::tag, Preference::score)
            .containsExactly(tuple("FOOD", 2), tuple("CULTURE", 1));
        // 선택을 바꾸면 이전 점수가 남지 않는다.
        assertThat(PreferenceDiscovery.evaluate(answers("PHOTO", "B", "MUST_EAT", "B")).preferences()).extracting(Preference::tag)
            .containsExactly("FOOD", "REST");
    }

    @Test void skippingOrNoPreferenceDoesNotInventPreferences() {
        assertThat(PreferenceDiscovery.evaluate(answers()).empty()).isTrue();
        var none = PreferenceDiscovery.evaluate(answers("SEASON", "ANY", "PHOTO", "ANY", "DAY_OFF", "SKIP"));
        assertThat(none.empty()).isTrue();
        assertThat(none.answered()).isEqualTo(2);
        assertThat(none.skipped()).isEqualTo(3);
    }

    @Test void rejectsForgedQuestionOptionOrOrder() {
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(answers("NOPE", "A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(answers("SEASON", "C"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(answers("SEASON", null))).isInstanceOf(IllegalArgumentException.class);
        // 이어지는 질문으로 시작하거나, 앞 답으로는 나올 수 없는 질문을 끼워 넣으면 거부한다.
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(answers("SUMMER_DAY", "A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(answers("SEASON", "B", "SUMMER_DAY", "A"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void pickedWeekendIsASaturdayOneWeekToOneMonthAhead() {
        var today = java.time.LocalDate.of(2026, 9, 29); // 화요일
        var seen = new HashSet<java.time.LocalDate>();
        var random = new java.util.Random(1);
        for (int i = 0; i < 200; i++) {
            var saturday = PreferenceDiscovery.pickWeekend(today, random);
            assertThat(saturday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
            assertThat(saturday).isBetween(today.plusDays(7), today.plusDays(30));
            seen.add(saturday);
        }
        // 10월 10·17·24일 중에서 고른다(10월 3일은 7일 안이라 제외).
        assertThat(seen).containsExactlyInAnyOrder(java.time.LocalDate.of(2026, 10, 10),
            java.time.LocalDate.of(2026, 10, 17), java.time.LocalDate.of(2026, 10, 24));
    }

    // 스키·눈썰매처럼 겨울에만 하는 장면이 문장에 들어가면 가까운 10월 주말이 아니라 겨울 성수기(12/20~2/10) 토요일을 고른다.
    @Test void seasonalSceneMovesTheWeekendIntoItsPeakSeason() {
        var today = java.time.LocalDate.of(2026, 9, 29);
        var winter = PreferenceDiscovery.evaluate(answers("SEASON", "B", "WINTER_DAY", "A"));
        assertThat(PreferenceDiscovery.season(winter)).isEqualTo(PreferenceDiscovery.Season.WINTER);
        var random = new java.util.Random(1);
        for (int i = 0; i < 100; i++) {
            var saturday = PreferenceDiscovery.pickWeekend(today, PreferenceDiscovery.Season.WINTER, random);
            assertThat(saturday.getDayOfWeek()).isEqualTo(java.time.DayOfWeek.SATURDAY);
            assertThat(saturday).isBetween(java.time.LocalDate.of(2026, 12, 20), java.time.LocalDate.of(2027, 2, 10));
            assertThat(PreferenceDiscovery.weekendFits(saturday, today, PreferenceDiscovery.Season.WINTER)).isTrue();
        }
        // 성수기가 거의 끝나 7일 뒤 토요일이 없으면 다음 해 성수기로 넘어간다.
        var late = PreferenceDiscovery.pickWeekend(java.time.LocalDate.of(2026, 8, 15), PreferenceDiscovery.Season.SUMMER, random);
        assertThat(late).isBetween(java.time.LocalDate.of(2027, 7, 20), java.time.LocalDate.of(2027, 8, 20));
        // 1월에는 이미 시작한 겨울 성수기 안에서 고른다.
        var january = PreferenceDiscovery.pickWeekend(java.time.LocalDate.of(2027, 1, 5), PreferenceDiscovery.Season.WINTER, random);
        assertThat(january).isBetween(java.time.LocalDate.of(2027, 1, 12), java.time.LocalDate.of(2027, 2, 10));
        // 계절 장면이 없으면 계절을 정하지 않고, 앞서 고른 10월 주말은 겨울 문장에 쓰지 않는다.
        assertThat(PreferenceDiscovery.season(PreferenceDiscovery.evaluate(answers("PHOTO", "B")))).isNull();
        assertThat(PreferenceDiscovery.weekendFits(java.time.LocalDate.of(2026, 10, 10), today, PreferenceDiscovery.Season.WINTER)).isFalse();
        assertThat(PreferenceDiscovery.weekendFits(java.time.LocalDate.of(2026, 10, 10), today, null)).isTrue();
    }

    @Test void seasonalScenesExistInQuestions() {
        var scenes = PreferenceDiscovery.QUESTIONS.stream().flatMap(q -> q.options().stream()).map(PreferenceDiscovery.Option::scene).toList();
        for (var answer : List.of(answers("SEASON", "A", "SUMMER_DAY", "A"), answers("SEASON", "A", "SUMMER_DAY", "B"), answers("SEASON", "B", "WINTER_DAY", "B")))
            assertThat(PreferenceDiscovery.season(PreferenceDiscovery.evaluate(answer))).isNotNull();
        assertThat(scenes).contains("스키장이나 눈썰매장에서 신나게 놀기");
    }

    @Test void tripSentenceHasDatesTopPreferencesAndScenes() {
        var saturday = java.time.LocalDate.of(2026, 10, 31);
        var result = PreferenceDiscovery.evaluate(answers("SEASON", "A", "SUMMER_DAY", "A", "SEA_TIME", "B"));
        assertThat(PreferenceDiscovery.tripSentence(result, saturday)).isEqualTo(
            "2026년 10월 31일 토요일부터 11월 1일 일요일까지 1박 2일로 국내 여행을 가고 싶어요. 바다, 체험·액티비티, 휴식 위주로 즐길 수 있는 곳이면 좋겠어요."
            + " 특히 '해수욕장에서 물놀이나 서핑하기', '바다가 보이는 카페에서 느긋하게 쉬기' 같은 여행이 끌려요.");
        // 이어지는 질문의 장면이 없으면 시작 질문 장면을 쓴다.
        assertThat(PreferenceDiscovery.tripSentence(PreferenceDiscovery.evaluate(answers("SEASON", "A")), saturday))
            .endsWith("특히 '여름, 시원한 물가에서 신나게 놀기' 같은 여행이 끌려요.");
        // 취향이 없으면 날짜만 넣고 취향을 지어내지 않는다.
        assertThat(PreferenceDiscovery.tripSentence(PreferenceDiscovery.evaluate(answers("SEASON", "SKIP")), saturday))
            .isEqualTo("2026년 10월 31일 토요일부터 11월 1일 일요일까지 1박 2일로 국내 여행을 가고 싶어요.");
    }

    @Test void everyQuestionIsWellFormed() {
        var ids = PreferenceDiscovery.QUESTIONS.stream().map(PreferenceDiscovery.Question::id).toList();
        assertThat(ids).doesNotHaveDuplicates().containsAll(PreferenceDiscovery.START_IDS);
        PreferenceDiscovery.QUESTIONS.forEach(q -> {
            assertThat(q.options()).hasSize(2);
            q.options().forEach(o -> {
                assertThat(PreferenceDiscovery.TAG_NAMES).containsKeys(o.tags().toArray(String[]::new));
                assertThat(ids).containsAll(o.next());
                // 화면은 질문·선택지 ID로 그림을 찾으므로 선택지마다 그림 파일이 있어야 한다.
                String image = "/static/images/discover/" + (q.id() + "-" + o.id()).toLowerCase() + ".svg";
                assertThat(getClass().getResource(image)).as(image).isNotNull();
            });
        });
        // 시작 질문이 아닌 질문은 모두 어떤 선택지에서 이어진다.
        var linked = new HashSet<String>();
        PreferenceDiscovery.QUESTIONS.forEach(q -> q.options().forEach(o -> linked.addAll(o.next())));
        ids.stream().filter(id -> !PreferenceDiscovery.START_IDS.contains(id)).forEach(id -> assertThat(linked).contains(id));
    }
}
