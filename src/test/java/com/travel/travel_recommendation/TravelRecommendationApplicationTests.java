package com.travel.travel_recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@AutoConfigureMockMvc
class TravelRecommendationApplicationTests {

    @Autowired
    private MockMvc mockMvc;

	@Test
	void homeRendersWithoutDatabase() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("index"))
                .andExpect(content().string(containsString("<h1>travel_recommendation</h1>")))
                .andExpect(content().string(containsString("Spring Boot 애플리케이션이 정상 실행되었습니다.")));
	}

}
