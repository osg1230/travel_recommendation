package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.LoginController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LoginController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("sqlserver-windows")
class LoginControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserDetailsService users;

    @Test
    void rendersLoginWithoutEchoingRequestCredentials() throws Exception {
        mvc.perform(get("/login").param("error", "").param("password", "Private123!"))
                .andExpect(status().isOk()).andExpect(view().name("login"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("Private123!"))));
    }

    @Test
    void databaseFailureOffersRetryWithoutLeakingDetails() throws Exception {
        when(users.loadUserByUsername(anyString())).thenThrow(new DataAccessResourceFailureException("private details"));
        mvc.perform(post("/login").servletPath("/login").with(csrf())
                .param("loginId", "test_user").param("password", "Private123!"))
                .andExpect(redirectedUrl("/login?unavailable")).andExpect(unauthenticated());
        mvc.perform(get("/login").param("unavailable", ""))
                .andExpect(content().string(containsString("잠시 후 다시 시도")))
                .andExpect(content().string(not(containsString("private details"))));
    }

    @Test
    void invalidInputAndCsrfDoNotReachDatabase() throws Exception {
        mvc.perform(post("/login").servletPath("/login").with(csrf()))
                .andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").servletPath("/login").with(csrf().useInvalidToken()))
                .andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }
}
