package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 선택형 취향 탐색 화면(SFR-02-05·06). DB 없는 기본 프로필의 예시 선택지(이름 값)로 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class PreferenceDiscoveryControllerTests {
    @Autowired MockMvc mvc;

    private MockHttpSession answered(String... pairs) throws Exception {
        var session = new MockHttpSession();
        var request = post("/travel/conditions/discover").session(session).with(csrf());
        for (int i = 0; i < pairs.length; i += 2) request.param("q_" + pairs[i], pairs[i + 1]);
        mvc.perform(request).andExpect(redirectedUrl("/travel/conditions/discover/result"));
        return session;
    }

    @Test void guestSeesQuestionsWithSkipAndNoPreferenceChoices() throws Exception {
        mvc.perform(get("/travel/conditions/discover")).andExpect(status().isOk())
            .andExpect(content().string(containsString("바다가 보이는 카페에서 느긋하게 쉬기")))
            .andExpect(content().string(containsString("상관없음")))
            .andExpect(content().string(containsString("건너뛰기")));
        mvc.perform(get("/travel/conditions")).andExpect(content().string(containsString("/travel/conditions/discover")));
    }

    @Test void resultExplainsScoresAndPreselectsTopPreferences() throws Exception {
        var session = answered("Q1", "A", "Q3", "B", "Q2", "SKIP");
        mvc.perform(get("/travel/conditions/discover/result").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("바다 · 2점")))
            .andExpect(content().string(containsString("고른 장면: 해변을 걷다가 노을 보기")))
            .andExpect(model().attribute("preselected", contains("바다", "자연", "휴식")));
        // 선택을 고치면 이전 답변은 남지 않는다.
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf()).param("q_Q1", "B"))
            .andExpect(redirectedUrl("/travel/conditions/discover/result"));
        mvc.perform(get("/travel/conditions/discover/result").session(session))
            .andExpect(model().attribute("preselected", contains("맛집", "문화·역사")));
    }

    @Test void forgedQuestionOrOptionIsRejected() throws Exception {
        mvc.perform(post("/travel/conditions/discover").with(csrf()).param("q_Q1", "Z")).andExpect(status().isBadRequest());
        mvc.perform(post("/travel/conditions/discover").with(csrf()).param("q_Q99", "A")).andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("화면에 없는 질문")));
        mvc.perform(post("/travel/conditions/discover").param("q_Q1", "A")).andExpect(status().isForbidden());
    }

    @Test void allSkippedDoesNotInventPreferences() throws Exception {
        var session = answered("Q1", "SKIP", "Q2", "ANY");
        mvc.perform(get("/travel/conditions/discover/result").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("도출된 취향이 없어요")))
            .andExpect(model().attribute("preselected", empty()));
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("discoveryMessage", containsString("고른 취향 없이")));
    }

    @Test void appliedPreferencesPrefillFormAndAreValidatedOnSubmit() throws Exception {
        var session = answered("Q1", "A");
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", "바다", "휴식"))
            .andExpect(redirectedUrl("/travel/conditions"));
        // 미리 채운 값은 검증 전이라 초안이 아니다.
        assertThat(session.getAttribute("travelConditionDraft")).isNull();
        mvc.perform(get("/travel/conditions").session(session))
            .andExpect(model().attribute("travelForm", hasProperty("themes", contains("바다", "휴식"))));
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("inputMode", "KEYWORD").param("themes", "바다", "휴식"))
            .andExpect(redirectedUrl("/travel/conditions/check"));
        assertThat(session.getAttribute("travelConditionPrefill")).isNull();
        mvc.perform(get("/travel/conditions/check").session(session))
            .andExpect(content().string(containsString("취향 탐색에서 고른 취향: 바다, 휴식")));
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", "없는취향"))
            .andExpect(status().isBadRequest());
    }

    @Test void conflictWithDirectlyChosenThemesAsksHowToApply() throws Exception {
        var session = answered("Q1", "A");
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("inputMode", "KEYWORD").param("themes", "맛집"))
            .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", "바다"))
            .andExpect(status().isOk()).andExpect(content().string(containsString("어떻게 반영할까요")))
            .andExpect(content().string(containsString("둘 다 반영하기")));
        var draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        assertThat(draft.getThemes()).containsExactly("맛집");
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", "바다").param("resolve", "merge"))
            .andExpect(redirectedUrl("/travel/conditions"));
        var prefill = (TravelConditionForm) session.getAttribute("travelConditionPrefill");
        assertThat(prefill.getThemes()).containsExactly("맛집", "바다");
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", "바다").param("resolve", "bad"))
            .andExpect(status().isBadRequest());
    }
}
