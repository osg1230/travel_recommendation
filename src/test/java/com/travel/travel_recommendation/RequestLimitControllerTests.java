package com.travel.travel_recommendation;

import com.travel.travel_recommendation.service.*;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 일정 작업·유료 검색·장소 찾기를 시작하기 전의 요청 수 제한을 DB 없이 확인한다. */
@SpringBootTest
@AutoConfigureMockMvc
class RequestLimitControllerTests {
    private static final String LIMITED="요청이 많아 잠시 뒤 다시 시도해 주세요(약 5분 뒤).";
    @Autowired MockMvc mvc;
    @MockitoBean AiRequestLimiter limiter;
    @MockitoBean ItineraryJobService schedules;
    @MockitoBean PlaceDiscoveryService discoveries;
    @MockitoBean PlaceCandidateService candidates;
    @MockitoBean PlaceOperatingService operating;
    private final UUID id=UUID.randomUUID();
    private String draft(String action) { return "/travel/recommendations/itineraries/"+id+"/"+action; }

    // 한도를 넘으면 새 일정 작업을 만들지 않고 보던 초안으로 돌아가 이유를 보여 준다.
    @Test void itineraryWorkIsNotStartedOverTheLimit() throws Exception {
        when(limiter.tryAcquireItinerary(any(),anyString())).thenReturn(LIMITED);
        mvc.perform(post(draft("recreate")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("recreateError",containsString(LIMITED)));
        mvc.perform(post(draft("retry")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("retryError",LIMITED));
        mvc.perform(post(draft("direct-revisions")).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("editError",containsString(LIMITED)));
        verify(schedules,never()).recreate(any(),any(),any());
        verify(schedules,never()).retry(any(),any(),any());
        verify(schedules,never()).reviseDirect(any(),any(),any(),any());
    }

    // 유료 검색도 같은 한도를 쓰고, 한도 안내는 그 절의 오류로 보여 준다.
    @Test void paidSearchesAreNotStartedOverTheLimit() throws Exception {
        when(limiter.tryAcquireItinerary(any(),anyString())).thenReturn(LIMITED);
        mvc.perform(post(draft("prices")).with(csrf())).andExpect(flash().attribute("priceError",LIMITED));
        mvc.perform(post(draft("verify")).with(csrf())).andExpect(flash().attribute("verifyError",LIMITED));
        verify(schedules,never()).lookupPrices(any(),any(),any());
        verify(schedules,never()).lookupVerifications(any(),any(),any());
    }

    // 가격을 찾는 동안 다른 탭에서 다시 눌러도 같은 세션이면 검색을 또 시작하지 않고, 끝난 뒤에도 잠시 간격을 둔다.
    @Test void priceLookupIsNotRepeatedWhileRunningOrRightAfter() throws Exception {
        var session=new MockHttpSession();
        var checked=new java.util.concurrent.atomic.AtomicReference<org.springframework.test.web.servlet.ResultActions>();
        when(schedules.lookupPrices(any(),any(),any())).thenAnswer(call->{
            checked.set(mvc.perform(post(draft("prices")).session(session).with(csrf())));
            return null;
        });
        mvc.perform(post(draft("prices")).session(session).with(csrf())).andExpect(status().is3xxRedirection());
        checked.get().andExpect(flash().attribute("priceError",containsString("가격을 찾는 중이거나")));
        mvc.perform(post(draft("prices")).session(session).with(csrf())).andExpect(flash().attribute("priceError",containsString("가격을 찾는 중이거나")));
        verify(schedules,times(1)).lookupPrices(any(),any(),any());
    }

    // '장소 더 찾아보기'는 여행지를 바꿔도 세션당 1분에 한 번이다. 번갈아 눌러 카카오·관광공사 한도를 쓰지 못하게 한다.
    @Test void placeLookupWaitsAMinuteEvenForAnotherDestination() throws Exception {
        var session=new MockHttpSession();
        when(discoveries.refresh(any(),anyLong(),any(),any())).thenReturn(1);
        mvc.perform(post("/travel/recommendations/"+id+"/destinations/1/places/refresh").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("lookupMessage",containsString("1곳의 정보를 확인")));
        mvc.perform(post("/travel/recommendations/"+id+"/destinations/2/places/refresh").session(session).with(csrf()))
            .andExpect(status().is3xxRedirection()).andExpect(flash().attribute("lookupMessage",containsString("1분 후 가능")));
        verify(discoveries,times(1)).refresh(any(),anyLong(),any(),any());
    }
}
