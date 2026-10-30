package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.FavoritePlace;
import com.travel.travel_recommendation.service.FavoriteService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class FavoriteControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean FavoriteService service;

    @Test void favoritesRequireLoginAndCsrf() throws Exception {
        mvc.perform(get("/my/favorites")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        mvc.perform(post("/my/favorites").with(csrf()).param("placeId","10"))
            .andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        mvc.perform(post("/my/favorites").with(user("member")).param("placeId","10"))
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void addsAndRemovesReturningToTheAllowedPage() throws Exception {
        String placePage="/travel/recommendations/8b6f0f2c-6a35-4b8f-9d0a-2f2a1b7c9d11/destinations/3/places";
        when(service.add(10L,"member")).thenReturn(true);
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf())
                .param("placeId","10").param("returnTo",placePage))
            .andExpect(redirectedUrl(placePage))
            .andExpect(flash().attribute("favoriteMessage",containsString("추가했어요")));
        mvc.perform(post("/my/favorites/10/delete").with(user("member")).with(csrf())
                .param("returnTo",placePage))
            .andExpect(redirectedUrl(placePage));
        verify(service).remove(10L,"member");
    }

    @Test void ignoresOutsideReturnAddress() throws Exception {
        when(service.add(10L,"member")).thenReturn(false);
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf())
                .param("placeId","10").param("returnTo","https://example.com/attack"))
            .andExpect(redirectedUrl("/my/favorites"))
            .andExpect(flash().attribute("favoriteMessage",containsString("이미 즐겨찾기")));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf())
                .param("placeId","10").param("returnTo","//example.com/attack"))
            .andExpect(redirectedUrl("/my/favorites"));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf())
                .param("placeId","10").param("returnTo","/admin"))
            .andExpect(redirectedUrl("/my/favorites"));
    }

    @Test void showsListAndRestaurantGuidance() throws Exception {
        when(service.list("member")).thenReturn(List.of(new FavoritePlace(1L,10L,"ATTRACTION","관광지","경포해변",
            "강릉","강원 강릉시","https://source","https://map",LocalDateTime.of(2026,9,20,1,0))));
        mvc.perform(get("/my/favorites").with(user("member"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("경포해변")))
            .andExpect(content().string(containsString("즐겨찾기 삭제")));
        when(service.add(20L,"member")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "음식점은 즐겨찾기에 등록할 수 없어요. 여행지·관광지·숙소만 등록할 수 있어요."));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","20"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("음식점은 즐겨찾기에 등록할 수 없어요")));
    }
}
