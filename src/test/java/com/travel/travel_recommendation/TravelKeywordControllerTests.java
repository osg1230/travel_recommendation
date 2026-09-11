package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.repository.KeywordRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class TravelKeywordControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean KeywordRepository keywords;
    private final TravelKeyword region = new TravelKeyword(101L, "REGION", "강원", "{\"value\":\"GANGWON\"}");
    private final TravelKeyword theme = new TravelKeyword(202L, "THEME", "바다", "{\"value\":\"SEA\"}");

    @BeforeEach void choices() { when(keywords.findActiveChoices()).thenReturn(List.of(region, theme)); }

    @Test void rendersIdsAndRejectsNamesWrongCategoriesAndUnknownIds() throws Exception {
        mvc.perform(get("/travel/conditions")).andExpect(status().isOk())
                .andExpect(content().string(containsString("value=\"101\"")))
                .andExpect(content().string(containsString("강원")))
                .andExpect(content().string(not(containsString("화면 확인용 예시"))));
        for (String invalid : List.of("강원", "202", "999")) {
            mvc.perform(post("/travel/conditions").with(csrf()).param("region", invalid))
                    .andExpect(model().attributeHasFieldErrors("travelForm", "region"));
        }
    }

    @Test void storesServerSnapshotAndKeepsNamesAfterCatalogChanges() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").with(csrf()).session(session)
                        .param("region", "101").param("themes", "202", "202")
                        .param("selectedKeywords[0].name", "위조"))
                .andExpect(status().is3xxRedirection());
        var draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        assertThat(draft.getSelectedKeywords()).containsExactlyInAnyOrder(region, theme);
        when(keywords.findActiveChoices()).thenReturn(List.of());
        mvc.perform(get("/travel/conditions/check").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("강원")))
                .andExpect(content().string(containsString("바다")));
        mvc.perform(post("/travel/conditions").with(csrf()).session(session).param("region", "101"))
                .andExpect(model().attributeHasFieldErrors("travelForm", "region"));
        assertThat(session.getAttribute("travelConditionDraft")).isSameAs(draft);
    }

    @Test void databaseFailurePreservesDraftAndTextStillWorks() throws Exception {
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").with(csrf()).session(session).param("region", "101"));
        Object draft = session.getAttribute("travelConditionDraft");
        when(keywords.findActiveChoices()).thenThrow(new DataAccessResourceFailureException("internal DB details"));
        mvc.perform(post("/travel/conditions").with(csrf()).session(session).param("region", "101"))
                .andExpect(status().isOk()).andExpect(model().attributeHasErrors("travelForm"))
                .andExpect(content().string(containsString("조건 목록을 불러오지 못했어요")))
                .andExpect(content().string(not(containsString("internal DB details"))));
        assertThat(session.getAttribute("travelConditionDraft")).isSameAs(draft);
        mvc.perform(post("/travel/conditions").with(csrf()).param("inputMode", "TEXT").param("originalText", "바다 여행"))
                .andExpect(status().is3xxRedirection());
    }

    @Test void emptyChoicesShowNoticeAndAllowUnspecifiedConditions() throws Exception {
        when(keywords.findActiveChoices()).thenReturn(List.of());
        mvc.perform(get("/travel/conditions")).andExpect(status().isOk())
                .andExpect(content().string(containsString("선택지는 준비 중이에요")));
        mvc.perform(post("/travel/conditions").with(csrf())).andExpect(status().is3xxRedirection());
    }
}
