package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.ProfileController;
import com.travel.travel_recommendation.service.KakaoMapClient;
import com.travel.travel_recommendation.service.ProfileService;
import com.travel.travel_recommendation.service.RecommendationException;
import com.travel.travel_recommendation.service.WithdrawalService;
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
import org.springframework.transaction.CannotCreateTransactionException;

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
    @MockitoBean WithdrawalService withdrawals;

    @Test
    void readFailureOffersRetry() throws Exception {
        when(profiles.getNickname("test_user")).thenThrow(new DataAccessResourceFailureException("private database details"));
        mvc.perform(get("/account").with(user("test_user")))
                .andExpect(status().isServiceUnavailable()).andExpect(view().name("account-unavailable"))
                .andExpect(content().string(not(containsString("private database details"))));
        // DB 연결 자체를 못 해 트랜잭션을 시작하지 못한 경우도 같은 안내를 보인다.
        doThrow(new CannotCreateTransactionException("private connection details")).when(profiles).getNickname("test_user");
        mvc.perform(get("/account").with(user("test_user")))
                .andExpect(status().isServiceUnavailable()).andExpect(view().name("account-unavailable"))
                .andExpect(content().string(not(containsString("private connection details"))));
    }

    @Test
    void withdrawalNoticeShowsActiveReservationsAndHidesFormForAdmins() throws Exception {
        when(profiles.getNickname(anyString())).thenReturn("여행자");
        when(withdrawals.countActiveReservations("test_user")).thenReturn(2);
        mvc.perform(get("/account").with(user("test_user")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("진행 중인 예약 2건은 탈퇴하면 취소되고, 예약·결제 내역도 삭제돼요.")))
                .andExpect(content().string(containsString("예약·결제 내역이 모두 삭제되며")))
                .andExpect(content().string(containsString("name=\"confirm\"")));
        // 관리자 계정은 탈퇴할 수 없으므로 탈퇴 폼 대신 안내를 본다.
        mvc.perform(get("/account").with(user("boss").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("관리자 계정은 탈퇴할 수 없어요")))
                .andExpect(content().string(not(containsString("name=\"confirm\""))));
        verify(withdrawals, never()).countActiveReservations("boss");
    }

    @Test
    void savedChangesAreListed() throws Exception {
        when(profiles.update(eq("test_user"), any())).thenReturn(new ProfileService.UpdateResult(true, true, true, false));
        mvc.perform(post("/account").with(user("test_user")).with(csrf()).param("nickname", "새닉네임")
                        .param("currentPassword", "Travel123!").param("homeArea", ""))
                .andExpect(redirectedUrl("/account"))
                .andExpect(flash().attribute("profileUpdated", "닉네임, 주로 출발하는 곳(지움)을 바꿨어요."));
        mvc.perform(get("/account").with(user("test_user")).flashAttr("profileUpdated", "닉네임을 바꿨어요."))
                .andExpect(content().string(containsString("닉네임을 바꿨어요.")));
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
        when(profiles.update(eq("test_user"), any())).thenReturn(new ProfileService.UpdateResult(true, false, false, false));
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
