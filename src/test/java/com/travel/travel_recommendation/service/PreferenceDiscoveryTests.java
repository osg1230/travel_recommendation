package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.service.PreferenceDiscovery.Preference;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PreferenceDiscoveryTests {
    @Test void scoresTagsOfChosenScenesAndExplainsWhy() {
        var result = PreferenceDiscovery.evaluate(Map.of("Q1", "A", "Q2", "A", "Q3", "B", "Q4", "ANY", "Q5", "SKIP"));
        assertThat(result.answered()).isEqualTo(4);
        assertThat(result.skipped()).isEqualTo(1);
        // Q1 A(바다·휴식) + Q2 A(자연·휴식) + Q3 B(바다·자연)
        assertThat(result.preferences()).extracting(Preference::tag, Preference::score)
            .containsExactly(tuple("SEA", 2), tuple("NATURE", 2), tuple("REST", 2));
        assertThat(result.preferences().getFirst().reasons())
            .containsExactly("바다가 보이는 카페에서 느긋하게 쉬기", "해변을 걷다가 노을 보기");
    }

    @Test void sameAnswersGiveSameResultAndChangedAnswersAreRecalculated() {
        var answers = Map.of("Q1", "B", "Q4", "A");
        assertThat(PreferenceDiscovery.evaluate(answers)).isEqualTo(PreferenceDiscovery.evaluate(new HashMap<>(answers)));
        assertThat(PreferenceDiscovery.evaluate(answers).preferences()).extracting(Preference::tag, Preference::score)
            .containsExactly(tuple("FOOD", 2), tuple("CULTURE", 1));
        // 선택을 바꾸면 이전 점수가 남지 않는다.
        assertThat(PreferenceDiscovery.evaluate(Map.of("Q1", "A", "Q4", "A")).preferences()).extracting(Preference::tag)
            .containsExactly("SEA", "REST", "FOOD");
    }

    @Test void skippingOrNoPreferenceDoesNotInventPreferences() {
        assertThat(PreferenceDiscovery.evaluate(Map.of()).empty()).isTrue();
        var none = PreferenceDiscovery.evaluate(Map.of("Q1", "ANY", "Q2", "ANY", "Q3", "SKIP"));
        assertThat(none.empty()).isTrue();
        assertThat(none.answered()).isEqualTo(2);
        assertThat(none.skipped()).isEqualTo(3);
    }

    @Test void rejectsForgedQuestionOrOption() {
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(Map.of("Q9", "A"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(Map.of("Q1", "C"))).isInstanceOf(IllegalArgumentException.class);
        var withNull = new HashMap<String, String>(); withNull.put("Q1", null);
        assertThatThrownBy(() -> PreferenceDiscovery.evaluate(withNull)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void everyTagMapsToAThemeKeyword() {
        PreferenceDiscovery.QUESTIONS.forEach(q -> {
            assertThat(q.options()).hasSize(2);
            q.options().forEach(o -> assertThat(PreferenceDiscovery.TAG_NAMES).containsKeys(o.tags().toArray(String[]::new)));
        });
        assertThat(PreferenceDiscovery.QUESTIONS).hasSizeBetween(3, 5);
    }
}
