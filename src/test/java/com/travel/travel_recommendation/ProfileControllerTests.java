package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.ProfileController;
import com.travel.travel_recommendation.service.ProfileService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
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
    void invalidLengthsNeverReachService() throws Exception {
        mvc.perform(post("/account").with(user("test_user")).with(csrf()).param("nickname", "가".repeat(51))
                .param("currentPassword", "가".repeat(64)).param("newPassword", "Ab1!".repeat(17)))
                .andExpect(model().attributeHasFieldErrors("profileForm", "nickname", "currentPassword", "newPassword"));
        verifyNoInteractions(profiles);
    }
}
