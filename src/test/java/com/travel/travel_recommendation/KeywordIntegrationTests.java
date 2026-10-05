package com.travel.travel_recommendation;

import com.travel.travel_recommendation.repository.KeywordRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockHttpSession;
import com.travel.travel_recommendation.dto.TravelConditionForm;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 002·003 SQL을 적용한 로컬 DB에서 실행하며 변경은 테스트 종료 시 롤백한다.
@EnabledIfEnvironmentVariable(named = "RUN_SQLSERVER_TESTS", matches = "true")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class KeywordIntegrationTests {
    @Autowired KeywordRepository keywords;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void databaseChoiceWorksThroughInputConfirmationAndEdit() throws Exception {
        var selected = keywords.findActiveChoices().stream().filter(k -> k.categoryCode().equals("REGION"))
                .findFirst().orElseThrow();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(post("/travel/conditions").with(csrf()).session(session).param("region", selected.id().toString()))
                .andExpect(redirectedUrl("/travel/conditions/check"));
        var draft = (TravelConditionForm) session.getAttribute("travelConditionDraft");
        assertThat(draft.getSelectedKeywords()).containsExactly(selected);
        mvc.perform(get("/travel/conditions/check").session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString(selected.name())));
        mvc.perform(get("/travel/conditions").session(session)).andExpect(status().isOk())
                // 지역은 선택 목록이 아니라 칩(라디오)이라 고른 값의 라디오가 checked로 다시 그려지는지 본다.
                .andExpect(content().string(org.hamcrest.Matchers.matchesRegex(
                        "(?s).*<input type=\"radio\"[^>]*value=\"" + selected.id() + "\"[^>]*checked=\"checked\".*")));
    }

    @Test void readsKoreanNamesJsonAndDatabaseOrder() {
        var choices = keywords.findActiveChoices();
        assertThat(choices).isNotEmpty();
        var seoul = choices.stream().filter(k -> k.name().equals("서울")).findFirst().orElseThrow();
        assertThat(seoul.id()).isPositive();
        assertThat(seoul.categoryCode()).isEqualTo("REGION");
        assertThat(seoul.valueJson()).isEqualTo("{\"value\":\"SEOUL\"}");
        jdbc.update("UPDATE dbo.keywords SET sort_order = 999 WHERE keyword_id = ?", seoul.id());
        var regions = keywords.findActiveChoices().stream().filter(k -> k.categoryCode().equals("REGION")).toList();
        assertThat(regions.getLast().id()).isEqualTo(seoul.id());
    }

    @Test void excludesInactiveKeywordsAndCategories() {
        var selected = keywords.findActiveChoices().getFirst();
        jdbc.update("UPDATE dbo.keywords SET is_active = 0 WHERE keyword_id = ?", selected.id());
        assertThat(keywords.findActiveChoices()).noneMatch(k -> k.id().equals(selected.id()));
        jdbc.update("UPDATE dbo.keyword_categories SET is_active = 0 WHERE category_code = ?", selected.categoryCode());
        assertThat(keywords.findActiveChoices()).noneMatch(k -> k.categoryCode().equals(selected.categoryCode()));
    }
}
