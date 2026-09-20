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
        when(service.save(job,"member")).thenReturn(new SavedItineraryService.Saved(7L,true));
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
            .andExpect(content().string(containsString("이 일정 삭제")));
    }

    @Test void showsGuidanceWhenDraftCannotBeSaved() throws Exception {
        UUID job=UUID.randomUUID();
        when(service.save(eq(job),anyString())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,
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
