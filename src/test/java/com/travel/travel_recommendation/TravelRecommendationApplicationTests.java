package com.travel.travel_recommendation;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import org.springframework.boot.test.context.SpringBootTest;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

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
                .andExpect(content().string(containsString("떠나고 싶은 마음만 알려 주세요")))
                .andExpect(content().string(containsString("이 문장으로 여행지 추천받기")));
	}

    // 컨트롤러가 없는 주소는 로그인 화면이나 403 대신 404가 된다(점검 보고 20번).
    @Test
    void unknownPathIsNotFoundForGuestsAndMembers() throws Exception {
        mockMvc.perform(get("/no-such-page")).andExpect(status().isNotFound());
        mockMvc.perform(get("/no-such-page").with(user("member1").roles("USER"))).andExpect(status().isNotFound());
    }

    // 컨트롤러가 있는 회원 화면은 그대로 로그인을 요구한다.
    @Test
    void knownMemberPathStillAsksForLogin() throws Exception {
        mockMvc.perform(get("/my/itineraries")).andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/login?required"));
    }

}
