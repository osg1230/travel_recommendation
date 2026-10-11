package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.ProfileController;
import com.travel.travel_recommendation.service.KakaoMapClient;
import com.travel.travel_recommendation.service.ProfileService;
import com.travel.travel_recommendation.service.RecommendationException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.function.Function;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(ProfileController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("sqlserver-windows")
class ProfileControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean ProfileService profiles;
    @MockitoBean KakaoMapClient maps;

    @Test
    void readFailureOffersRetry() throws Exception {
        when(profiles.getNickname("test_user")).thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/account").with(user("test_user")))
                .andExpect(status().isServiceUnavailable()).andExpect(view().name("account-unavailable"))
                .andExpect(content().string(not(containsString("private database details"))));
    }

    @Test
    void saveFailuresKeepNicknameAndClearEveryPassword() throws Exception {
        for (var failure : new org.springframework.dao.DataAccessException[]{
                new DataAccessResourceFailureException("private details"), new DataIntegrityViolationException("private details")}) {
            when(profiles.update(eq("test_user"), any())).thenThrow(failure);
            mvc.perform(post("/account").with(user("test_user")).with(csrf()).param("nickname", "입력유지")
                    .param("currentPassword", "Travel123!").param("newPassword", "NewTravel123!")
                    .param("newPasswordConfirm", "NewTravel123!"))
                    .andExpect(status().isOk()).andExpect(view().name("account"))
                    .andExpect(content().string(containsString("입력유지")))
                    .andExpect(content().string(not(containsString("Travel123!"))))
                    .andExpect(content().string(not(containsString("private details"))));
        }
    }

    @Test
    void unknownHomeAreaAsksToRetypeAndSavedValueIsNotRechecked() throws Exception {
        // 요청마다 새 빌더를 만든다. 같은 빌더에 param을 더하면 값이 쌓인다.
        Function<String, MockHttpServletRequestBuilder> request = homeArea -> post("/account").with(user("test_user")).with(csrf()).param("nickname", "여행자")
                        .param("currentPassword", "Travel123!").param("homeArea", homeArea);
        // 못 찾는 곳이면 칸 오류를 보이고 저장하지 않는다.
        doThrow(new RecommendationException("MAP_ORIGIN", "'없는동네' 위치를 찾지 못했어요. 시·구·동 이름이나 역 이름으로 다시 적어 주세요."))
                .when(maps).findOrigin("없는동네");
        mvc.perform(request.apply("없는동네")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("profileForm", "homeArea"))
                .andExpect(content().string(containsString("위치를 찾지 못했어요")));
        // 지도 서비스 문제면 다시 시도하거나 비우라고 안내하고 내부 메시지는 보이지 않는다.
        doThrow(new RecommendationException("MAP_TIMEOUT", "private map details")).when(maps).findOrigin("대전역");
        mvc.perform(request.apply("대전역")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("profileForm", "homeArea"))
                .andExpect(content().string(containsString("비우고 저장")))
                .andExpect(content().string(not(containsString("private map details"))));
        verify(profiles, never()).update(anyString(), any());
        // 이미 저장된 값 그대로면 지도를 찾지 않고 저장으로 넘어간다(비밀번호만 바꿀 때).
        when(profiles.getHomeArea("test_user")).thenReturn("서울 송파구");
        mvc.perform(request.apply("서울 송파구")).andExpect(redirectedUrl("/account"));
        verify(maps, never()).findOrigin("서울 송파구");
        verify(profiles).update(eq("test_user"), any());
    }

    @Test
    void invalidLengthsNeverReachService() throws Exception {
        mvc.perform(post("/account").with(user("test_user")).with(csrf()).param("nickname", "가".repeat(51))
                .param("currentPassword", "가".repeat(64)).param("newPassword", "Ab1!".repeat(17)))
                .andExpect(model().attributeHasFieldErrors("profileForm", "nickname", "currentPassword", "newPassword"));
        verifyNoInteractions(profiles);
    }
}
