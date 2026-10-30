package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.LoginController;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.assertj.core.api.Assertions.assertThat;
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
@ExtendWith(OutputCaptureExtension.class)
class LoginControllerTests {
    @Autowired MockMvc mvc;
    @Autowired PasswordEncoder encoder;
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
        // 화면을 오래 열어 두어 CSRF 토큰이 사라졌으면 403 화면 대신 다시 로그인하라고 안내한다.
        mvc.perform(post("/login").servletPath("/login").with(csrf().useInvalidToken()))
                .andExpect(redirectedUrl("/login?expired")).andExpect(unauthenticated());
        mvc.perform(get("/login").param("expired", ""))
                .andExpect(content().string(containsString("화면을 오래 열어 두어")));
        verifyNoInteractions(users);
    }

    @Test
    void expiredSessionLogoutGoesHomeAndProtectedPageExplainsLogin() throws Exception {
        // 세션이 끝난 뒤 누른 로그아웃은 이미 로그아웃된 것과 같다.
        mvc.perform(post("/logout")).andExpect(redirectedUrl("/?logout"));
        mvc.perform(get("/login").param("required", ""))
                .andExpect(content().string(containsString("로그인이 필요하거나 로그인 시간이 지났어요")));
    }

    @Test
    void restrictedAccountIsToldOnlyAfterCorrectPasswordAndOnlyLoginIdIsRefilled(CapturedOutput output) throws Exception {
        when(users.loadUserByUsername("test_user")).thenReturn(User.withUsername("test_user")
                .password(encoder.encode("Right123!")).roles("MEMBER").accountLocked(true).build());
        // 비밀번호가 틀리면 이용 제한 여부를 알리지 않는다.
        var failed = mvc.perform(post("/login").servletPath("/login").with(csrf())
                .param("loginId", "test_user").param("password", "Wrong123!"))
                .andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated()).andReturn();
        // 다시 연 로그인 화면에는 아이디만 채워져 있다.
        mvc.perform(get("/login").session((MockHttpSession) failed.getRequest().getSession()))
                .andExpect(content().string(containsString("value=\"test_user\"")))
                .andExpect(content().string(not(containsString("Wrong123!"))));
        mvc.perform(post("/login").servletPath("/login").with(csrf())
                .param("loginId", "test_user").param("password", "Right123!"))
                .andExpect(redirectedUrl("/login?restricted")).andExpect(unauthenticated());
        mvc.perform(get("/login").param("restricted", ""))
                .andExpect(content().string(containsString("이용이 제한된 계정")));
        // 실패 기록에는 가린 아이디와 사유만 남고 비밀번호는 남지 않는다.
        assertThat(output.getOut()).contains("reason=LOGIN_BadCredentialsException loginId=te***")
                .contains("reason=LOGIN_LockedException loginId=te***")
                .doesNotContain("Wrong123!").doesNotContain("Right123!").doesNotContain("test_user");
    }

    @Test
    void returnPathAllowsOnlyKnownInternalPages() {
        assertThat(SecurityConfig.isReturnPath("/my/itineraries/7")).isTrue();
        assertThat(SecurityConfig.isReturnPath("/my/reservations/new?stockId=3&date=2026-11-01")).isTrue();
        assertThat(SecurityConfig.isReturnPath("/account")).isTrue();
        assertThat(SecurityConfig.isReturnPath("//example.com/my/itineraries")).isFalse();
        assertThat(SecurityConfig.isReturnPath("/admin/members")).isFalse();
        assertThat(SecurityConfig.isReturnPath("/my/../admin")).isFalse();
        assertThat(SecurityConfig.isReturnPath("/my/itineraries\r\nSet-Cookie:x")).isFalse();
    }
}
