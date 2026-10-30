package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.PlaceCandidateService;
import com.travel.travel_recommendation.service.PlaceOperatingService;
import org.springframework.mock.web.MockHttpSession;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import java.time.LocalDateTime;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class PlaceCandidateControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean PlaceCandidateService service;
    @MockitoBean PlaceOperatingService operating;
    // DB 프로필에서만 있는 평가 서비스를 흉내 내 요약·최근 평가 화면만 확인한다.
    @MockitoBean com.travel.travel_recommendation.service.PlaceRatingService ratings;

    @Test void showsRecentRatingsWithMaskedNicknameAndEscapedComment() throws Exception {
        UUID id = UUID.randomUUID();
        var time = LocalDateTime.of(2026, 10, 28, 15, 25);
        var destination = new DestinationCandidate(1L, "강릉", "강원특별자치도 강릉시", "", "https://visitgangneung.net/", time);
        var attraction = new PlaceCandidate(10L, "경포대", "주소", "문화", "설명",
            "https://www.gn.go.kr/", "https://map.kakao.com/link/search/test", time, null, null, "SYSTEM", null);
        when(service.find(eq(id), eq(1L), isNull(), isNull())).thenReturn(
            new PlaceCandidateView(id, destination, 1, false, List.of(attraction), List.of(), List.of()));
        var recent = List.of(
            new PlaceRatingView.Recent("김**", 5, List.of("경치가 좋아요"), "노을이 <b>예뻐요</b>", LocalDateTime.of(2026, 10, 28, 14, 0)),
            new PlaceRatingView.Recent("박**", 3, List.of(), null, LocalDateTime.of(2026, 10, 27, 9, 30)));
        when(ratings.summaries(any())).thenReturn(Map.of(10L,
            new PlaceRatingView.Summary(4.0, 2, List.of(new PlaceRatingView.TagCount("경치가 좋아요", 1)), recent)));
        mvc.perform(get("/travel/recommendations/" + id + "/destinations/1/places"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("★ 4.0 · 평가 2개 · 경치가 좋아요")))
            .andExpect(content().string(containsString("최근 평가 2건 보기")))
            .andExpect(content().string(containsString("김** · 2026-10-28 14:00")))
            .andExpect(content().string(containsString("노을이 &lt;b&gt;예뻐요&lt;/b&gt;")))
            .andExpect(content().string(not(containsString("<b>예뻐요"))))
            // 한줄평이 없는 평가도 별점은 보인다.
            .andExpect(content().string(containsString("박** · 2026-10-27 09:30")))
            .andExpect(content().string(containsString("★★★☆☆")));
    }

    @Test void operatingLookupRequiresCsrfAndLimitsRepeatedCalls() throws Exception {
        UUID id=UUID.randomUUID(); var session=new MockHttpSession();
        String url="/travel/recommendations/"+id+"/destinations/1/places/10/operating-info";
        mvc.perform(post(url).session(session)).andExpect(status().isForbidden());
        verifyNoInteractions(operating);
        when(operating.refresh(eq(id),eq(1L),eq(10L),isNull(),isNull())).thenReturn(false);
        mvc.perform(post(url).session(session).with(csrf())).andExpect(status().isSeeOther())
            .andExpect(flash().attribute("lookupMessage",containsString("연결하지 못했어요")));
        mvc.perform(post(url).session(session).with(csrf())).andExpect(status().isSeeOther())
            .andExpect(flash().attribute("lookupMessage",containsString("1분 후")));
        verify(operating,times(1)).refresh(eq(id),eq(1L),eq(10L),isNull(),isNull());
    }

    @Test void databaseFailureShowsSafeRetryGuidance() throws Exception {
        when(service.find(any(), anyLong(), any(), any())).thenThrow(
            new DataAccessResourceFailureException("private database connection details"));
        mvc.perform(get("/travel/recommendations/" + UUID.randomUUID() + "/destinations/1/places"))
            .andExpect(status().isServiceUnavailable()).andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(content().string(containsString("잠시 후 다시 시도")))
            .andExpect(content().string(not(containsString("private database"))));
    }

    @Test void placeTextIsEscapedAndEachSectionRendersOnce() throws Exception {
        UUID id = UUID.randomUUID();
        var time = LocalDateTime.of(2026, 9, 17, 15, 25);
        var destination = new DestinationCandidate(1L, "강릉", "강원특별자치도 강릉시", "", "https://visitgangneung.net/", time);
        var attraction = new PlaceCandidate(10L, "<script>alert(1)</script>", "주소", "문화", "설명",
            "https://www.gn.go.kr/", "https://map.kakao.com/link/search/test", time,null,null,"SYSTEM",
            new PlaceOperatingInfo("123","14","<script>hours</script>","연중무휴(부분 휴관)","","",java.time.Instant.now()));
        when(service.find(eq(id), eq(1L), isNull(), isNull())).thenReturn(
            new PlaceCandidateView(id, destination, 1, false, List.of(attraction), List.of(), List.of()));
        mvc.perform(get("/travel/recommendations/" + id + "/destinations/1/places"))
            .andExpect(status().isOk()).andExpect(content().string(not(containsString("<script>alert"))))
            .andExpect(content().string(not(containsString("<script>hours"))))
            .andExpect(content().string(containsString("연중무휴(부분 휴관)")))
            .andExpect(content().string(containsString("&lt;script&gt;")))
            .andExpect(result -> org.assertj.core.api.Assertions.assertThat(result.getResponse().getContentAsString())
                .containsOnlyOnce("<article class=\"place-row\" id=\"place-10\">"));
    }
}
