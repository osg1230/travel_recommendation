package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.service.TravelConditionOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** DB 키워드(ID 값)와 탐색 태그(value_json)가 맞게 연결되는지 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
class PreferenceDiscoveryIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired TravelConditionOptions options;

    private String id(String name) {
        return options.getChoices().get("themes").stream().filter(k -> k.name().equals(name))
            .map(TravelKeyword::getSelectionValue).findFirst().orElseThrow();
    }

    @Test void derivedTagsMapToThemeKeywordIdsAndPassValidation() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(post("/travel/conditions/discover").session(session).with(csrf()).param("q_Q1", "A").param("q_Q3", "B"))
            .andExpect(redirectedUrl("/travel/conditions/discover/result"));
        mvc.perform(get("/travel/conditions/discover/result").session(session))
            .andExpect(model().attribute("preselected", contains(id("바다"), id("자연"), id("휴식"))));
        mvc.perform(post("/travel/conditions/discover/apply").session(session).with(csrf()).param("themes", id("바다"), id("휴식")))
            .andExpect(redirectedUrl("/travel/conditions"));
        mvc.perform(post("/travel/conditions").session(session).with(csrf()).param("inputMode", "KEYWORD").param("themes", id("바다"), id("휴식")))
            .andExpect(redirectedUrl("/travel/conditions/check"));
        mvc.perform(get("/travel/conditions/check").session(session))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("바다, 휴식")));
    }
}
