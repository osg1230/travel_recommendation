package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.PlaceRatingView;
import com.travel.travel_recommendation.service.PlaceRatingService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 평가 둘러보기 화면(DB 없이, 평가 서비스는 흉내 냄). */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceRatingControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean PlaceRatingService ratings;

    @Test void browseRequiresLoginAndListsRecentRatingsByPlace() throws Exception {
        mvc.perform(get("/my/ratings/browse")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        var place=new PlaceRatingView.PlaceInfo(10L,"경포대","ATTRACTION","관광지","강원특별자치도 강릉시 경포로 365");
        var summary=new PlaceRatingView.Summary(4.5,2,List.of(new PlaceRatingView.TagCount("경치가 좋아요",1)),List.of(
            new PlaceRatingView.Recent("라**",4,List.of(),null,LocalDateTime.of(2026,10,28,20,49)),
            new PlaceRatingView.Recent("김**",5,List.of("경치가 좋아요"),"노을이 <b>예뻐요</b>",LocalDateTime.of(2026,10,28,20,40))));
        // 검색어는 앞뒤 공백을 지운 뒤 서비스에 넘기고 입력칸에 다시 채운다.
        when(ratings.browse("경포")).thenReturn(List.of(new PlaceRatingView.RatedPlace(place,summary)));
        mvc.perform(get("/my/ratings/browse").param("q"," 경포 ").with(user("member"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("경포대")))
            .andExpect(content().string(containsString("★ 4.5 · 평가 2개 · 경치가 좋아요")))
            .andExpect(content().string(containsString("라** · 2026-10-28 20:49")))
            .andExpect(content().string(containsString("김** · 2026-10-28 20:40")))
            .andExpect(content().string(containsString("노을이 &lt;b&gt;예뻐요&lt;/b&gt;")))
            .andExpect(content().string(containsString("value=\"경포\"")))
            .andExpect(content().string(containsString("returnTo=/my/ratings/browse")));
        when(ratings.browse("")).thenReturn(List.of());
        mvc.perform(get("/my/ratings/browse").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("아직 남겨진 평가가 없어요")));
        mvc.perform(get("/my/ratings/browse").param("q","없는곳").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("“없는곳”이(가) 든 장소의 평가를 찾지 못했어요")));
        mvc.perform(get("/my/ratings/browse").param("q","가".repeat(51)).with(user("member"))).andExpect(status().isBadRequest());
    }
}
