package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.PreferenceDiscoveryController;
import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.service.PreferenceDiscovery;
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

    /** 첫 질문을 SEASON으로 고정한 세션. 실제로는 시작 질문 중 무작위로 고른다. */
    private static MockHttpSession startingWithSeason() {
        var session = new MockHttpSession();
        session.setAttribute(PreferenceDiscoveryController.FIRST_KEY, "SEASON");
        return session;
    }

    private void answer(MockHttpSession session, int step, String question, String answer) throws Exception {
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf())
            .param("question", question).param("answer", answer).param("step", String.valueOf(step)))
            .andExpect(status().is3xxRedirection());
    }

    @Test void firstQuestionIsRandomStartQuestionWithSkipAndNoPreferenceChoices() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/travel/conditions/discover").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("상관없음")))
            .andExpect(content().string(containsString("건너뛰기")));
        var first = (String) session.getAttribute(PreferenceDiscoveryController.FIRST_KEY);
        assertThat(PreferenceDiscovery.START_IDS).contains(first);
        // 새로고침해도 같은 첫 질문이 나온다.
        mvc.perform(get("/travel/conditions/discover").session(session))
            .andExpect(model().attribute("question", PreferenceDiscovery.question(first)));
        mvc.perform(get("/travel/conditions")).andExpect(content().string(containsString("/travel/conditions/discover")));
    }

    @Test void nextQuestionFollowsChosenAnswer() throws Exception {
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "A");
        mvc.perform(get("/travel/conditions/discover").session(session))
            .andExpect(content().string(containsString("여름 낮에 하고 싶은 건?")))
            .andExpect(content().string(containsString("여름과 겨울 중 어느 쪽이 더 좋아요? → 여름, 시원한 물가에서 신나게 놀기")));
        // 앞 답을 바꾸면 그 뒤 답은 지우고 바뀐 답에 맞는 질문이 이어진다.
        answer(session, 1, "SUMMER_DAY", "A");
        answer(session, 0, "SEASON", "B");
        mvc.perform(get("/travel/conditions/discover").session(session))
            .andExpect(model().attribute("question", PreferenceDiscovery.question("WINTER_DAY")))
            .andExpect(model().attribute("step", 1));
    }

    @Test void resultAfterFiveAnswersExplainsScores() throws Exception {
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "A");
        answer(session, 1, "SUMMER_DAY", "A");
        answer(session, 2, "SEA_TIME", "B");
        answer(session, 3, "AFTERNOON", "A");
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf())
            .param("question", "NIGHT").param("answer", "SKIP").param("step", "4"))
            .andExpect(redirectedUrl("/travel/conditions/discover/result"));
        mvc.perform(get("/travel/conditions/discover/result").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("바다 · 3점")))
            .andExpect(content().string(containsString("고른 장면: 해수욕장에서 물놀이나 서핑하기")))
            .andExpect(model().attribute("preselected", contains("바다", "휴식", "체험·액티비티")));
    }

    @Test void forgedQuestionOptionOrOrderIsRejected() throws Exception {
        var session = startingWithSeason();
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf())
            .param("question", "SEASON").param("answer", "Z").param("step", "0")).andExpect(status().isBadRequest());
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf())
            .param("question", "SUMMER_DAY").param("answer", "A").param("step", "0")).andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("화면에 없는 질문")));
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf())
            .param("question", "SEASON").param("answer", "A").param("step", "3")).andExpect(status().isBadRequest());
        mvc.perform(post("/travel/conditions/discover").session(session)
            .param("question", "SEASON").param("answer", "A").param("step", "0")).andExpect(status().isForbidden());
    }

    @Test void allSkippedDoesNotInventPreferences() throws Exception {
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "SKIP");
        answer(session, 1, "PHOTO", "ANY");
        mvc.perform(get("/travel/conditions/discover/result").session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("도출된 취향이 없어요")))
            .andExpect(model().attribute("preselected", empty()));
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("discoveryMessage", containsString("고른 취향 없이")));
    }

    @Test void resetStartsOverWithNewFirstQuestion() throws Exception {
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "A");
        mvc.perform(post("/travel/conditions/discover/reset").session(session).with(csrf()))
            .andExpect(redirectedUrl("/travel/conditions/discover"));
        assertThat(session.getAttribute(PreferenceDiscoveryController.ANSWERS_KEY)).isNull();
        assertThat(session.getAttribute(PreferenceDiscoveryController.FIRST_KEY)).isNull();
    }

    @Test void appliedPreferencesPrefillFormAndAreValidatedOnSubmit() throws Exception {
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "A");
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
        var session = startingWithSeason();
        answer(session, 0, "SEASON", "A");
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
