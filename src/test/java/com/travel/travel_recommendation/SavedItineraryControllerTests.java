package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.SavedItinerary;
import com.travel.travel_recommendation.service.SavedItineraryService;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SavedItineraryControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean SavedItineraryService service;
    @MockitoBean com.travel.travel_recommendation.service.TransitRouteService transitRoutes;
    // DB 프로필에서만 있는 평가 서비스를 흉내 내 저장 일정 상세의 최근 평가 표시만 확인한다.
    @MockitoBean com.travel.travel_recommendation.service.PlaceRatingService ratings;

    @Test void showsRecentRatingsOfSavedPlaces() throws Exception {
        var summary=new SavedItinerary.Summary(7L,"강릉 2일 일정","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,
            LocalDateTime.of(2026,9,20,1,0));
        var item=new SavedItinerary.Item("VISIT","관광",LocalTime.of(9,0),LocalTime.of(10,30),"경포해변","확인 필요","https://map","https://source",null,10L);
        when(service.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(summary,"자동차",
            List.of(new SavedItinerary.Day(1,LocalDate.of(2026,10,1),List.of(item)))));
        when(ratings.summaries(any())).thenReturn(Map.of(10L,new com.travel.travel_recommendation.dto.PlaceRatingView.Summary(5.0,1,List.of(),
            List.of(new com.travel.travel_recommendation.dto.PlaceRatingView.Recent("이**",5,List.of("다시 가고 싶어요"),"아침 산책하기 좋아요",
                LocalDateTime.of(2026,10,28,8,0))))));
        mvc.perform(get("/my/itineraries/7").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("★ 5.0 · 평가 1개")))
            .andExpect(content().string(containsString("최근 평가 1건 보기")))
            .andExpect(content().string(containsString("이** · 2026-10-28 08:00")))
            .andExpect(content().string(containsString("다시 가고 싶어요")))
            .andExpect(content().string(containsString("아침 산책하기 좋아요")));
    }

    @Test void savingRequiresLoginAndCsrf() throws Exception {
        UUID job=UUID.randomUUID();
        mvc.perform(post("/my/itineraries").with(csrf()).param("jobId",job.toString()))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        mvc.perform(get("/my/itineraries")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        mvc.perform(post("/my/itineraries").with(user("member")).param("jobId",job.toString()))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void savesDraftAndShowsSavedItinerary() throws Exception {
        UUID job=UUID.randomUUID();
        when(service.save(eq(job),eq("member"),any())).thenReturn(new SavedItineraryService.Saved(7L,true));
        mvc.perform(post("/my/itineraries").with(user("member")).with(csrf()).param("jobId",job.toString()))
            .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/my/itineraries/7"))
            .andExpect(flash().attribute("savedMessage",containsString("저장했어요")));
        var summary=new SavedItinerary.Summary(7L,"강릉 2일 일정","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,
            LocalDateTime.of(2026,9,20,1,0));
        var item=new SavedItinerary.Item("VISIT","관광",LocalTime.of(9,0),LocalTime.of(10,30),"경포해변","확인 필요","https://map","https://source",null);
        when(service.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(summary,"자동차",
            List.of(new SavedItinerary.Day(1,LocalDate.of(2026,10,1),List.of(item)))));
        mvc.perform(get("/my/itineraries/7").with(user("member"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("강릉 2일 일정")))
            .andExpect(content().string(containsString("경포해변")))
            .andExpect(content().string(containsString("이 일정 삭제")))
            // 자동차 일정에는 대중교통 실제 경로 보기를 붙이지 않는다.
            .andExpect(content().string(org.hamcrest.Matchers.not(containsString("data-day-paths"))));
    }

    // 대중교통 저장 일정: 지도에서 그날 실제 경로를 조회할 주소를 주고, 요청하면 그날 구간만 지금 조회해 JSON으로 돌려준다.
    @Test void transitItineraryLooksUpDayPathsOnRequest() throws Exception {
        var summary=new SavedItinerary.Summary(8L,"강릉 2일 일정","강릉",LocalDate.of(2026,11,21),LocalDate.of(2026,11,22),2,
            LocalDateTime.of(2026,10,28,1,0));
        var first=new SavedItinerary.Item("VISIT","관광",LocalTime.of(9,0),LocalTime.of(10,30),"경포해변","확인 필요",
            "https://map.kakao.com/link/map/경포해변,37.8055,128.9087","https://source",null);
        var second=new SavedItinerary.Item("VISIT","관광",LocalTime.of(11,0),LocalTime.of(12,0),"오죽헌","확인 필요",
            "https://map.kakao.com/link/map/오죽헌,37.7795,128.8785","https://source",null);
        when(service.detail(8L,"member")).thenReturn(new SavedItinerary.Detail(summary,"대중교통",
            List.of(new SavedItinerary.Day(1,LocalDate.of(2026,11,21),List.of(first,second)))));
        mvc.perform(get("/my/itineraries/8").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-day-paths=\"/my/itineraries/8/day-paths\"")));
        var piece=new com.travel.travel_recommendation.service.KakaoMapClient.PathPiece("BUS",
            List.of(new double[]{37.8055,128.9087},new double[]{37.7795,128.8785}));
        when(transitRoutes.day(any())).thenReturn(new com.travel.travel_recommendation.service.TransitRouteService.DayPath(1,List.of(List.of(piece)),0));
        mvc.perform(get("/my/itineraries/8/day-paths").param("day","1").with(user("member")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(jsonPath("$.day").value(1)).andExpect(jsonPath("$.legs[0][0].mode").value("BUS"))
            .andExpect(jsonPath("$.legs[0][0].points[1][0]").value(37.7795));
        // 없는 날은 404이고 경로를 조회하지 않는다.
        mvc.perform(get("/my/itineraries/8/day-paths").param("day","3").with(user("member"))).andExpect(status().isNotFound());
        verify(transitRoutes,times(1)).day(any());
    }

    @Test void dayPathsNeedLoginAndTransitItinerary() throws Exception {
        mvc.perform(get("/my/itineraries/7/day-paths").param("day","1")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        var summary=new SavedItinerary.Summary(7L,"강릉 2일 일정","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,
            LocalDateTime.of(2026,9,20,1,0));
        when(service.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(summary,"자동차",List.of()));
        mvc.perform(get("/my/itineraries/7/day-paths").param("day","1").with(user("member"))).andExpect(status().isBadRequest());
        verifyNoInteractions(transitRoutes);
    }

    @Test void showsGuidanceWhenDraftCannotBeSaved() throws Exception {
        UUID job=UUID.randomUUID();
        when(service.save(eq(job),anyString(),any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
            "여행 날짜가 정해지지 않은 초안이에요. 여행 조건에서 출발일을 정하고 일정을 다시 만들어 주세요."));
        mvc.perform(post("/my/itineraries").with(user("member")).with(csrf()).param("jobId",job.toString()))
            .andExpect(status().isConflict()).andExpect(content().string(containsString("출발일을 정하고")));
    }

    @Test void listsEmptyStateAndRenamesTitle() throws Exception {
        when(service.list("member")).thenReturn(List.of());
        mvc.perform(get("/my/itineraries").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("아직 저장한 일정이 없어요")));
        mvc.perform(post("/my/itineraries/7/title").with(user("member")).with(csrf()).param("title","가을 강릉 여행"))
            .andExpect(redirectedUrl("/my/itineraries/7"));
        verify(service).rename(7L,"member","가을 강릉 여행");
        mvc.perform(post("/my/itineraries/7/delete").with(user("member")).with(csrf()))
            .andExpect(redirectedUrl("/my/itineraries"));
        verify(service).delete(7L,"member");
    }
}
