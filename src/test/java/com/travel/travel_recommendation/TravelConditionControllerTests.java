package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import java.time.LocalDate;
import java.time.ZoneId;
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

    // 지난 출발일은 막으므로 오늘(한국 시간)부터 며칠 뒤 날짜로 시험한다.
    private static String day(int daysFromToday) { return LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(daysFromToday).toString(); }

    @Test
    void guestCanOpenFormAndMissingDraftReturnsToInput() throws Exception {
        mvc.perform(get("/travel/conditions")).andExpect(status().isOk())
                .andExpect(content().string(containsString("여행지 추천받기")))
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
                        .param("startDate", day(10)).param("endDate", day(12))
                        .param("themes", "바다", "바다", "휴식").param("budget", "0")
                        .param("moods", "한적한", "한적한").param("pace", "느긋하게")
                        .param("originalText", "다른 입력 방법").param("userId", "999").param("role", "ADMIN"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        TravelConditionForm draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        assertThat(draft.getDurationDays()).isEqualTo("3");
        assertThat(draft.getThemes()).containsExactly("바다", "휴식");
        assertThat(draft.getMoods()).containsExactly("한적한");
        assertThat(draft.getKeywordNames("PACE")).isEqualTo("느긋하게");
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
                .andExpect(content().string(containsString("&lt;script&gt;")));
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
                        .param("season", "unknown").param("companion", "unknown").param("transportation", "unknown")
                        .param("moods", "없는 분위기").param("pace", "unknown"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "region", "themes", "season", "companion", "transportation", "moods", "pace"));
    }

    @Test
    void datesMustBeCompleteRealAndConsistentWithDuration() throws Exception {
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(10)))
                .andExpect(model().attributeHasFieldErrors("travelForm", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", "2026-02-30").param("endDate", "not-a-date"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "startDate", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(12)).param("endDate", day(10)))
                .andExpect(model().attributeHasFieldErrors("travelForm", "endDate"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(10)).param("endDate", day(12)).param("durationDays", "2"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "durationDays"));
        // 출발일과 여행 일수만 입력하면 마지막 날은 서버가 계산해 채운다.
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("startDate", day(10)).param("durationDays", "3"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        assertThat(((TravelConditionForm) session.getAttribute("travelConditionDraft")).getEndDate()).isEqualTo(day(12));
    }

    @Test
    void tripsLongerThanSevenDaysAndPastStartDatesAreRejected() throws Exception {
        // 일정은 최대 7일까지 만든다. 7일은 되고 8일은 안 된다.
        mvc.perform(post("/travel/conditions").with(csrf()).param("durationDays", "7"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("durationDays", "8"))
                .andExpect(model().attributeHasFieldErrorCode("travelForm", "durationDays", "tooLong"))
                .andExpect(content().string(containsString("7일까지")));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(10)).param("endDate", day(17)))
                .andExpect(model().attributeHasFieldErrorCode("travelForm", "endDate", "tooLong"));
        // 오늘 출발은 되고, 어제 출발은 고쳐 달라고 안내한다.
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(0)).param("durationDays", "1"))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", day(-1)).param("durationDays", "1"))
                .andExpect(model().attributeHasFieldErrorCode("travelForm", "startDate", "past"))
                .andExpect(content().string(containsString("출발일은 오늘이나 그 이후 날짜로 골라 주세요.")));
    }

    @Test
    void seasonThatDoesNotMatchStartMonthIsRejected() throws Exception {
        LocalDate start = LocalDate.now(ZoneId.of("Asia/Seoul")).plusDays(10);
        int month = start.getMonthValue();
        String season = month >= 3 && month <= 5 ? "봄" : month >= 6 && month <= 8 ? "여름" : month >= 9 && month <= 11 ? "가을" : "겨울";
        String other = "여름".equals(season) ? "겨울" : "여름";
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", start.toString()).param("durationDays", "2").param("season", season))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(post("/travel/conditions").with(csrf()).param("startDate", start.toString()).param("durationDays", "2").param("season", other))
                .andExpect(model().attributeHasFieldErrorCode("travelForm", "season", "mismatch"))
                .andExpect(content().string(containsString("출발일(" + month + "월)과 계절(" + other + ")이 맞지 않아요. 둘 중 하나를 고쳐 주세요.")));
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
