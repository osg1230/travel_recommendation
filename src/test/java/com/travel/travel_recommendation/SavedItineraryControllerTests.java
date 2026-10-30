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
import static org.hamcrest.Matchers.not;
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
    // 일정 상세의 즐겨찾기 등록·해제 버튼 표시를 확인하려고 즐겨찾기 서비스도 흉내 낸다.
    @MockitoBean com.travel.travel_recommendation.service.FavoriteService favorites;

    // 관광지·숙소에는 즐겨찾기 버튼을, 이미 평가한 장소에는 내 별점을 보여 주고, 누른 뒤 그 항목 위치로 돌아오게 한다.
    @Test void detailOffersFavoriteToggleAndShowsMyRating() throws Exception {
        var summary=new SavedItinerary.Summary(7L,"강릉 2일 일정","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,
            LocalDateTime.of(2026,9,20,1,0));
        var visit=new SavedItinerary.Item("VISIT","관광",LocalTime.of(9,0),LocalTime.of(10,30),"경포해변","확인 필요","https://map","https://source",null,10L);
        var meal=new SavedItinerary.Item("MEAL","식사",LocalTime.of(12,0),LocalTime.of(13,0),"초당순두부","확인 필요","https://map2","https://source2",null,20L);
        var stay=new SavedItinerary.Item("STAY","숙박",LocalTime.of(20,0),LocalTime.of(23,59),"바다호텔","확인 필요","https://map3","https://source3",null,30L);
        when(service.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(summary,"자동차",
            List.of(new SavedItinerary.Day(1,LocalDate.of(2026,10,1),List.of(visit,meal,stay)))));
        when(ratings.myScores(any(),eq("member"))).thenReturn(Map.of(10L,4));
        when(favorites.registeredPlaceIds("member")).thenReturn(Set.of(30L));
        mvc.perform(get("/my/itineraries/7").with(user("member"))
                .flashAttr("favoriteMessage","즐겨찾기에 추가했어요.").flashAttr("favoriteAnchor","stop-1-2"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("내 평가 ★4 · 바꾸기")))
            .andExpect(content().string(containsString("이 장소 평가하기")))
            .andExpect(content().string(containsString("returnTo=/my/itineraries/7%23stop-1-0")))
            // 관광지는 추가, 이미 등록한 숙소는 삭제 버튼이고, 음식점에는 즐겨찾기 버튼이 없다.
            .andExpect(content().string(containsString("action=\"/my/favorites\"")))
            .andExpect(content().string(containsString("action=\"/my/favorites/30/delete\"")))
            .andExpect(content().string(not(containsString("action=\"/my/favorites/20/delete\""))))
            .andExpect(content().string(containsString("value=\"/my/itineraries/7#stop-1-0\"")))
            .andExpect(content().string(not(containsString("value=\"/my/itineraries/7#stop-1-1\""))))
            // 누른 항목(1일차 세 번째) 옆에만 결과 안내가 한 번 보인다.
            .andExpect(content().string(containsString("즐겨찾기에 추가했어요.")));
        String html=mvc.perform(get("/my/itineraries/7").with(user("member"))
                .flashAttr("savedMessage","‘경포해변’ 평가를 등록했어요.").flashAttr("ratingAnchor","stop-1-0"))
            .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        // 평가하고 돌아온 안내는 위쪽이 아니라 그 항목 옆에 한 번만 보인다.
        org.assertj.core.api.Assertions.assertThat(html.split("‘경포해변’ 평가를 등록했어요.",-1)).hasSize(2);
        org.assertj.core.api.Assertions.assertThat(html.indexOf("‘경포해변’ 평가를 등록했어요.")).isGreaterThan(html.indexOf("id=\"stop-1-0\""));
    }

    // 원래 초안이 없는 일정의 '수정 준비하기'는 오래 걸릴 수 있어 처리 중 표시를 두고, 실제와 맞는 안내를 보여 준다.
    @Test void prepareEditShowsLoadingAndAccurateGuidance() throws Exception {
        var summary=new SavedItinerary.Summary(7L,"강릉 2일 일정","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,2),2,
            LocalDateTime.of(2026,9,20,1,0));
        when(service.detail(7L,"member")).thenReturn(new SavedItinerary.Detail(summary,"자동차",List.of()));
        mvc.perform(get("/my/itineraries/7").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("data-submit-loading=\"수정을 준비하는 중… (최대 1분)\"")))
            .andExpect(content().string(containsString("예전에 저장한 일정은 일부 조건이 이어지지 않을 수 있어요")))
            .andExpect(content().string(not(containsString("필수 방문 장소 설정은 이어지지 않아요"))));
    }

    // 오류 화면에서 원래 보던 화면으로 돌아갈 수 있게 한다. 찾을 수 없음(404)은 같은 오류가 다시 나므로 링크를 두지 않는다.
    @Test void errorPageLinksBackToWhereTheUserWas() throws Exception {
        UUID job=UUID.randomUUID();
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 먼저 바뀌었어요.")).when(service).apply(eq(7L),eq(job),anyString(),eq("member"));
        mvc.perform(post("/my/itineraries/7/apply").with(user("member")).with(csrf()).param("jobId",job.toString()).param("version","v1"))
            .andExpect(status().isConflict())
            .andExpect(content().string(containsString("href=\"/travel/recommendations/itineraries/"+job+"\"")))
            .andExpect(content().string(containsString("이전 화면으로")));
        when(service.save(eq(job),eq("member"),any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"초안이 아직 완성되지 않았어요."));
        mvc.perform(post("/my/itineraries").with(user("member")).with(csrf()).param("jobId",job.toString()))
            .andExpect(content().string(containsString("href=\"/travel/recommendations/itineraries/"+job+"\"")));
        doThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"일정 이름을 적어 주세요.")).when(service).rename(7L,"member"," ");
        mvc.perform(post("/my/itineraries/7/title").with(user("member")).with(csrf()).param("title"," "))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("href=\"/my/itineraries/7\"")));
        when(service.undo(7L,"v1","member")).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("no connection"));
        mvc.perform(post("/my/itineraries/7/undo").with(user("member")).with(csrf()).param("version","v1"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("href=\"/my/itineraries/7\"")));
        doThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"저장한 일정을 찾을 수 없어요.")).when(service).rename(8L,"member","새 이름");
        mvc.perform(post("/my/itineraries/8/title").with(user("member")).with(csrf()).param("title","새 이름"))
            .andExpect(status().isNotFound())
            .andExpect(content().string(not(containsString("이전 화면으로"))));
    }

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
            .andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/my/itineraries")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
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
        mvc.perform(get("/my/itineraries/7/day-paths").param("day","1")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
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
