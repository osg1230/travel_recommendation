package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.LoginController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.unauthenticated;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 다른 테스트와 달리 로그인 횟수 제한을 켜 둔 채 실제 로그인 필터를 지나가게 해 본다. */
@WebMvcTest(value=LoginController.class,properties="travel.login-limit.enabled=true")
@Import(SecurityConfig.class)
@ActiveProfiles("sqlserver-windows")
class LoginLimitControllerTests {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
    @MockitoBean UserDetailsService users;

    private MockHttpServletRequestBuilder login(String loginId,String password) {
        return post("/login").servletPath("/login").with(csrf()).param("loginId",loginId).param("password",password);
    }

    @Test void afterFiveWrongPasswordsEvenTheRightOneIsNotChecked() throws Exception {
        when(users.loadUserByUsername("limit_user")).thenReturn(User.withUsername("limit_user").password(encoder.encode("Right1234!")).roles("MEMBER").build());
        for(int i=0;i<5;i++) mvc.perform(login("limit_user","Wrong1234!")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(login("limit_user","Right1234!")).andExpect(redirectedUrl("/login?limited")).andExpect(unauthenticated());
        verify(users,times(5)).loadUserByUsername("limit_user");
        mvc.perform(get("/login").param("limited","")).andExpect(content().string(containsString("로그인 시도가 너무 많아")));
    }

    @Test void successResetsTheCountForThatLoginId() throws Exception {
        when(users.loadUserByUsername("reset_user")).thenReturn(User.withUsername("reset_user").password(encoder.encode("Right1234!")).roles("MEMBER").build());
        for(int i=0;i<4;i++) mvc.perform(login("reset_user","Wrong1234!")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(login("reset_user","Right1234!")).andExpect(redirectedUrl("/"));
        for(int i=0;i<5;i++) mvc.perform(login("reset_user","Wrong1234!")).andExpect(redirectedUrl("/login?error"));
    }
}
