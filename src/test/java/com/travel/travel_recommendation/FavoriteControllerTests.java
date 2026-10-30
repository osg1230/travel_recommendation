package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.FavoritePlace;
import com.travel.travel_recommendation.service.FavoriteService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
        // 허용 경로처럼 시작해도 뒤가 정해진 모양이 아니면 받지 않는다.
        for(String outside:new String[]{"/my/favorites/../admin","/travel/recommendations/../../admin","/my/itineraries/7#evil","/my/itineraries/7/delete"})
            mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","10").param("returnTo",outside))
                .andExpect(redirectedUrl("/my/favorites"));
    }

    // 장소 후보·저장 일정 상세에서 누르면 그 항목 위치로 돌아가고, 항목 옆에 안내를 보여 줄 위치 이름을 넘긴다.
    @Test void returnsToTheItemThatWasClicked() throws Exception {
        String placeItem="/travel/recommendations/8b6f0f2c-6a35-4b8f-9d0a-2f2a1b7c9d11/destinations/3/places#place-10";
        when(service.add(10L,"member")).thenReturn(true);
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","10").param("returnTo",placeItem))
            .andExpect(redirectedUrl(placeItem)).andExpect(flash().attribute("favoriteAnchor","place-10"));
        when(service.remove(10L,"member")).thenReturn(Optional.of(new FavoriteService.Removed("경포해변",true)));
        mvc.perform(post("/my/favorites/10/delete").with(user("member")).with(csrf()).param("returnTo","/my/itineraries/7#stop-1-2"))
            .andExpect(redirectedUrl("/my/itineraries/7#stop-1-2"))
            .andExpect(flash().attribute("favoriteAnchor","stop-1-2"))
            .andExpect(flash().attribute("favoriteMessage","‘경포해변’ 즐겨찾기를 삭제했어요."));
    }

    // 두 번 누르거나 두 탭에서 눌러 이미 처리된 경우는 오류 화면 대신 원래 화면에서 안내한다.
    @Test void repeatedClicksAreGuidedInsteadOfFailing() throws Exception {
        when(service.add(10L,"member")).thenThrow(new DataIntegrityViolationException("UQ_favorites_user_place"));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","10").param("returnTo","/my/itineraries/7#stop-1-0"))
            .andExpect(redirectedUrl("/my/itineraries/7#stop-1-0"))
            .andExpect(flash().attribute("favoriteMessage","이미 즐겨찾기에 있는 장소예요."));
        when(service.remove(10L,"member")).thenReturn(Optional.empty());
        mvc.perform(post("/my/favorites/10/delete").with(user("member")).with(csrf()).param("returnTo","/my/favorites"))
            .andExpect(redirectedUrl("/my/favorites"))
            .andExpect(flash().attribute("favoriteMessage","이미 즐겨찾기에서 빠진 장소예요."))
            .andExpect(flash().attribute("removedPlaceId",nullValue()));
    }

    // 목록에서 지우면 장소 이름과 되돌리기 버튼(같은 장소 다시 등록)을 보여 준다. 비활성 장소는 되돌리기를 두지 않는다.
    @Test void removalOffersUndoOnTheList() throws Exception {
        when(service.remove(10L,"member")).thenReturn(Optional.of(new FavoriteService.Removed("경포해변",true)));
        mvc.perform(post("/my/favorites/10/delete").with(user("member")).with(csrf()).param("returnTo","/my/favorites"))
            .andExpect(redirectedUrl("/my/favorites"))
            .andExpect(flash().attribute("favoriteMessage","‘경포해변’ 즐겨찾기를 삭제했어요."))
            .andExpect(flash().attribute("removedPlaceId",10L));
        when(service.remove(11L,"member")).thenReturn(Optional.of(new FavoriteService.Removed("문 닫은 곳",false)));
        mvc.perform(post("/my/favorites/11/delete").with(user("member")).with(csrf()).param("returnTo","/my/favorites"))
            .andExpect(flash().attribute("removedPlaceId",nullValue()));
        when(service.list("member")).thenReturn(List.of());
        mvc.perform(get("/my/favorites").with(user("member"))
                .flashAttr("favoriteMessage","‘경포해변’ 즐겨찾기를 삭제했어요.").flashAttr("removedPlaceId",10L))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("‘경포해변’ 즐겨찾기를 삭제했어요.")))
            .andExpect(content().string(containsString("되돌리기")))
            .andExpect(content().string(containsString("name=\"placeId\" value=\"10\"")))
            .andExpect(content().string(containsString("등록일시는 지금으로 바뀌어요")));
        mvc.perform(get("/my/favorites").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(not(containsString("되돌리기"))));
    }

    @Test void errorPageLinksBackToThePreviousScreen() throws Exception {
        when(service.add(10L,"member")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"장소를 찾을 수 없어요."));
        String placeItem="/travel/recommendations/8b6f0f2c-6a35-4b8f-9d0a-2f2a1b7c9d11/destinations/3/places#place-10";
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","10").param("returnTo",placeItem))
            .andExpect(status().isNotFound())
            .andExpect(content().string(containsString("이전 화면으로")))
            .andExpect(content().string(containsString("href=\""+placeItem+"\"")));
        // 트랜잭션을 시작하지 못한 오류도 안내 화면으로 보여 준다. 돌아갈 주소가 없으면 '이전 화면으로'는 없다.
        when(service.add(11L,"member")).thenThrow(new CannotCreateTransactionException("no connection"));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","11"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("즐겨찾기를 처리하지 못했어요")))
            .andExpect(content().string(not(containsString("이전 화면으로"))));
    }

    @Test void showsListAndRestaurantGuidance() throws Exception {
        when(service.list("member")).thenReturn(List.of(new FavoritePlace(1L,10L,"ATTRACTION","관광지","경포해변",
            "강릉","강원 강릉시","https://source","https://map",LocalDateTime.of(2026,9,20,1,0),true),
            new FavoritePlace(2L,20L,"ACCOMMODATION","숙소","문 닫은 숙소",
            "강릉","강원 강릉시","https://old-source","https://old-map",LocalDateTime.of(2026,9,19,1,0),false)));
        mvc.perform(get("/my/favorites").with(user("member"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("경포해변")))
            .andExpect(content().string(containsString("즐겨찾기 삭제")))
            // 관광지·숙소는 평가 화면으로 이어 주고, 평가하고 나면 즐겨찾기 목록으로 돌아온다.
            .andExpect(content().string(containsString("href=\"/my/ratings/10?returnTo=/my/favorites\"")))
            // 비활성 장소는 안내와 삭제 버튼만 보여 주고 예전 링크는 보여 주지 않는다.
            .andExpect(content().string(containsString("더 이상 확인되지 않는 장소예요")))
            .andExpect(content().string(containsString("/my/favorites/20/delete")))
            .andExpect(content().string(not(containsString("https://old-source"))))
            .andExpect(content().string(not(containsString("/my/ratings/20"))));
        when(service.add(20L,"member")).thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "음식점은 즐겨찾기에 등록할 수 없어요. 여행지·관광지·숙소만 등록할 수 있어요."));
        mvc.perform(post("/my/favorites").with(user("member")).with(csrf()).param("placeId","20"))
            .andExpect(status().isBadRequest())
            .andExpect(content().string(containsString("음식점은 즐겨찾기에 등록할 수 없어요")));
    }
}
