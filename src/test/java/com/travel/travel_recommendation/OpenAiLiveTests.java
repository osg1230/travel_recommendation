package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.service.ConditionReviewService;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

// 유료 API는 명시적으로 선택한 경우에만 예시 문장 한 건으로 확인한다.
@EnabledIfEnvironmentVariable(named = "RUN_OPENAI_TESTS", matches = "true")
@SpringBootTest
@ActiveProfiles("sqlserver-windows")
class OpenAiLiveTests {
    @Autowired ConditionReviewService service;
    @Autowired TravelConditionOptions options;

    @Test void extractsOneSyntheticTravelRequestUsingDatabaseChoices() {
        TravelConditionForm draft = new TravelConditionForm();
        draft.setOriginalText("친구와 둘이 강릉으로 2박 3일 바다 여행을 가고 싶어요. 총예산은 60만원이고 자차로 이동해요. 숙박이 필요하고 날짜는 아직 미정이에요.");
        var form = service.analyze(draft, options.getChoices()).form();
        assertThat(form.getRegion()).contains("강릉");
        assertThat(form.getDurationDays()).isEqualTo("3");
        assertThat(form.getPartySize()).isEqualTo("2");
        assertThat(new java.math.BigDecimal(form.getBudget())).isEqualByComparingTo("600000");
        assertThat(form.getStartDate()).isEmpty();
        assertThat(form.getEndDate()).isEmpty();
        assertThat(form.getAccommodationNeeded()).isEqualTo("true");
    }
}
