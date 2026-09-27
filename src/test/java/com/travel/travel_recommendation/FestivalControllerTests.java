package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.TravelConditionController;
import com.travel.travel_recommendation.dto.RegionalFestival;
import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.service.RecommendationException;
import com.travel.travel_recommendation.service.TourApiClient;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 홈의 지역 축제와 축제로 조건 입력 시작하기. 관광공사 호출은 가짜 응답으로 바꾼다. */
@SpringBootTest
@AutoConfigureMockMvc
class FestivalControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean TourApiClient tourApi;

    private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Seoul"));

    @Test void homeDoesNotCallTourApiUntilRegionIsChosen() throws Exception {
        mvc.perform(get("/")).andExpect(status().isOk())
            .andExpect(content().string(containsString("축제 따라 여행 떠나기")))
            .andExpect(content().string(containsString("festivalRegion=43")));
        verify(tourApi, never()).searchFestivals(any(), any());
    }

    @Test void chosenRegionShowsFestivalsAndFailureKeepsHomeUsable() throws Exception {
        when(tourApi.configured()).thenReturn(true);
        when(tourApi.searchFestivals(eq("44"), any())).thenReturn(List.of(
            new RegionalFestival("506376", "강경젓갈축제", today.plusDays(5), today.plusDays(8), "충청남도 논산시", "금강둔치", "", "041-730-2971")));
        mvc.perform(get("/").param("festivalRegion", "44")).andExpect(status().isOk())
            .andExpect(content().string(containsString("강경젓갈축제")))
            .andExpect(content().string(containsString("이 축제 기간으로 일정 만들기")));

        when(tourApi.searchFestivals(eq("41"), any())).thenThrow(new RecommendationException("TOUR_TIMEOUT", "관광정보 조회 시간이 초과됐어요. 다시 시도해 주세요."));
        mvc.perform(get("/").param("festivalRegion", "41")).andExpect(status().isOk())
            .andExpect(content().string(containsString("관광정보 조회 시간이 초과됐어요")))
            .andExpect(content().string(containsString("이 문장으로 여행지 추천받기")));
    }

    @Test void festivalPrefillsRegionAndDatesFromServerData() throws Exception {
        when(tourApi.configured()).thenReturn(true);
        when(tourApi.searchFestivals(eq("47"), any())).thenReturn(List.of(
            new RegionalFestival("900168", "안동 축제", today.minusDays(2), today.plusDays(7), "경상북도 안동시", "", "", "")));
        var session = new MockHttpSession();
        mvc.perform(post("/travel/conditions/festival").session(session).with(csrf()).param("province", "47").param("festivalId", "900168"))
            .andExpect(redirectedUrl("/travel/conditions"))
            .andExpect(flash().attribute("festivalMessage", containsString("안동 축제")));
        var form = (TravelConditionForm) session.getAttribute(TravelConditionController.PREFILL_KEY);
        // 이미 시작한 축제는 오늘부터 1박 2일로 채운다. 기본 프로필의 예시 선택지는 이름이 선택값이다.
        assertThat(form.getRegion()).isEqualTo("경북");
        assertThat(form.getStartDate()).isEqualTo(today.toString());
        assertThat(form.getEndDate()).isEqualTo(today.plusDays(1).toString());

        mvc.perform(post("/travel/conditions/festival").with(csrf()).param("province", "47").param("festivalId", "1"))
            .andExpect(redirectedUrl("/?festivalRegion=47#festivals"))
            .andExpect(flash().attribute("festivalError", containsString("다시 찾지 못했어요")));
        mvc.perform(post("/travel/conditions/festival").with(csrf()).param("province", "99").param("festivalId", "1"))
            .andExpect(redirectedUrl("/"));
    }
}
