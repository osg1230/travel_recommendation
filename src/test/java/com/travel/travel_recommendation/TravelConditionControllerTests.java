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

@SpringBootTest
@AutoConfigureMockMvc
class TravelConditionControllerTests {
    @Autowired MockMvc mvc;

    @Test
    void guestCanOpenFormAndMissingDraftReturnsToInput() throws Exception {
        mvc.perform(get("/travel/conditions")).andExpect(status().isOk())
                .andExpect(content().string(containsString("입력 내용 확인하기")))
                .andExpect(content().string(containsString("name=\"_csrf\"")));
        mvc.perform(get("/travel/conditions/check")).andExpect(redirectedUrl("/travel/conditions"));
    }

    @Test
    void unselectedConditionsRemainOptional() throws Exception {
        var response = mvc.perform(post("/travel/conditions").with(csrf()).param("inputMode", "KEYWORD"))
                .andExpect(redirectedUrl("/travel/conditions/check")).andReturn();
        mvc.perform(get("/travel/conditions/check").session((MockHttpSession) response.getRequest().getSession()))
                .andExpect(status().isOk()).andExpect(content().string(containsString("상관없음")));
    }

    @Test
    void keywordConditionsNormalizeAndSurviveEditWithoutClientOwnershipFields() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").session(session).with(csrf())
                        .param("inputMode", "KEYWORD").param("region", " 강원 ")
                        .param("startDate", "2026-10-01").param("endDate", "2026-10-03")
                        .param("themes", "바다", "바다", "휴식").param("budget", "0")
                        .param("originalText", "다른 입력 방법").param("userId", "999").param("role", "ADMIN"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        TravelConditionForm draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        assertThat(draft.getDurationDays()).isEqualTo("3");
        assertThat(draft.getThemes()).containsExactly("바다", "휴식");
        assertThat(draft.getOriginalText()).isEmpty();
        mvc.perform(get("/travel/conditions/check").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("바다, 휴식")))
                .andExpect(content().string(containsString("0원")));
        mvc.perform(get("/travel/conditions").session(session)).andExpect(status().isOk())
                .andExpect(model().attribute("travelForm", hasProperty("region", is("강원"))));
        mvc.perform(get("/travel/conditions/check").session(new MockHttpSession()))
                .andExpect(redirectedUrl("/travel/conditions"));
    }

    @Test
    void textIsEscapedAndNotPresentedAsAnalyzedConditions() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("inputMode", "TEXT")
                        .param("originalText", " <script>alert(1)</script> 바다 여행 ").param("budget", "invalid"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(get("/travel/conditions/check").session(session)).andExpect(status().isOk())
                .andExpect(content().string(not(containsString("<script>"))))
                .andExpect(content().string(containsString("&lt;script&gt;")))
                .andExpect(content().string(containsString("아직 문장을 분석하지 않았어요")));
        assertThat(((TravelConditionForm) session.getAttribute("travelConditionDraft")).getBudget()).isEmpty();
    }

    @Test
    void blankAndOversizedTextAreRejectedButLimitIsAccepted() throws Exception {
        for (String text : new String[]{"  ", "가".repeat(2001)}) {
            mvc.perform(post("/travel/conditions").with(csrf()).param("inputMode", "TEXT").param("originalText", text))
                    .andExpect(status().isOk()).andExpect(model().attributeHasFieldErrors("travelForm", "originalText"));
        }
        mvc.perform(post("/travel/conditions").with(csrf()).param("inputMode", "TEXT").param("originalText", "가".repeat(2000)))
                .andExpect(status().is3xxRedirection());
    }

    @Test
    void invalidModeAndChoicesAreRejected() throws Exception {
        mvc.perform(post("/travel/conditions").with(csrf()).param("inputMode", "OTHER"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "inputMode"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("region", "해외").param("themes", "없는 항목")
                        .param("season", "unknown").param("companion", "unknown").param("transportation", "unknown"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "region", "themes", "season", "companion", "transportation"));
    }

    @Test
    void datesMustBeCompleteRealAndConsistentWithDuration() throws Exception {
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", "2026-10-01"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", "2026-02-30").param("endDate", "not-a-date"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "startDate", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", "2026-10-03").param("endDate", "2026-10-01"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", "2026-10-01").param("endDate", "2026-10-03").param("durationDays", "2"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "durationDays"));
    }

    @Test
    void invalidNumbersDoNotOverwritePreviousDraft() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("region", "제주"))
                .andExpect(status().is3xxRedirection());
        for (String value : new String[]{"-1", "1e5", "1.234", "10000000000", "abc"}) {
            mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("budget", value).param("durationDays", "0"))
                    .andExpect(model().attributeHasFieldErrors("travelForm", "budget", "durationDays"));
        }
        assertThat(((TravelConditionForm) session.getAttribute("travelConditionDraft")).getRegion()).isEqualTo("제주");
    }

    @Test
    void csrfIsRequiredAndAuthenticatedUsersCanAlsoEnterConditions() throws Exception {
        mvc.perform(post("/travel/conditions")).andExpect(status().isForbidden());
        mvc.perform(post("/travel/conditions").with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        mvc.perform(post("/travel/conditions").with(user("member")).with(csrf()).param("durationDays", "1"))
                .andExpect(status().is3xxRedirection());
        mvc.perform(get("/travel/conditions").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
    }
}
