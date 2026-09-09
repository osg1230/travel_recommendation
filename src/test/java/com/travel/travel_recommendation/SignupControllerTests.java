package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.SignupController;
import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.service.DuplicateUserException;
import com.travel.travel_recommendation.service.UserService;
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

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SignupController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("sqlserver-windows")
class SignupControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;

    private MockHttpServletRequestBuilder validRequest() {
        return post("/signup").param("loginId", " traveler_01 ").param("nickname", " 여행자 ")
                .param("password", "Sample123!").param("passwordConfirm", "Sample123!");
    }

    @Test
    void formRendersWithCsrfTokenAndEmptyPasswords() throws Exception {
        mvc.perform(get("/signup")).andExpect(status().isOk()).andExpect(view().name("signup"))
                .andExpect(content().string(containsString("name=\"_csrf\"")))
                .andExpect(content().string(not(containsString("value=\"Sample123!\""))));
    }

    @Test
    void normalizesInputAndRedirectsAfterSignup() throws Exception {
        mvc.perform(validRequest().with(csrf()).param("role", "ADMIN").param("status", "RESTRICTED"))
                .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/signup/complete"))
                .andExpect(flash().attribute("signupComplete", true));
        verify(users).signup(argThat(form -> form.getLoginId().equals("traveler_01") && form.getNickname().equals("여행자")));
    }

    @Test
    void missingOrInvalidCsrfNeverCallsService() throws Exception {
        mvc.perform(validRequest()).andExpect(status().isForbidden());
        mvc.perform(validRequest().with(csrf().useInvalidToken())).andExpect(status().isForbidden());
        verifyNoInteractions(users);
    }

    @Test
    void blankInputAndPasswordMismatchAreRejected() throws Exception {
        mvc.perform(post("/signup").with(csrf())).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("signupForm", "loginId", "nickname", "password", "passwordConfirm"));
        mvc.perform(post("/signup").with(csrf()).param("loginId", "valid_id").param("nickname", "여행자")
                        .param("password", "Sample123!").param("passwordConfirm", "Other123!"))
                .andExpect(model().attributeHasFieldErrors("signupForm", "passwordMatching"))
                .andExpect(content().string(not(containsString("Sample123!"))))
                .andExpect(content().string(not(containsString("Other123!"))));
        verifyNoInteractions(users);
    }

    @Test
    void duplicateFieldsKeepNicknameButNeverEchoPassword() throws Exception {
        for (String field : new String[]{"loginId", "nickname"}) {
            doThrow(new DuplicateUserException(field, "이미 사용 중입니다.")).when(users).signup(any(SignupForm.class));
            mvc.perform(validRequest().with(csrf())).andExpect(status().isOk())
                    .andExpect(model().attributeHasFieldErrors("signupForm", field))
                    .andExpect(content().string(containsString("value=\"여행자\"")))
                    .andExpect(content().string(not(containsString("value=\"Sample123!\""))))
                    .andExpect(content().string(not(containsString("Sample123!"))));
        }
    }

    @Test
    void databaseFailuresShowSafeRetryMessage() throws Exception {
        doThrow(new DataIntegrityViolationException("private database details")).when(users).signup(any());
        mvc.perform(validRequest().with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("아이디나 닉네임")))
                .andExpect(content().string(not(containsString("private database details"))));
        doThrow(new DataAccessResourceFailureException("private connection details")).when(users).signup(any());
        mvc.perform(validRequest().with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("잠시 후 다시 시도")))
                .andExpect(content().string(not(containsString("private connection details"))));
    }

    @Test
    void successPageRequiresSuccessfulSignupFlash() throws Exception {
        mvc.perform(get("/signup/complete")).andExpect(redirectedUrl("/signup"));
        mvc.perform(get("/signup/complete").flashAttr("signupComplete", true))
                .andExpect(status().isOk()).andExpect(view().name("signup-complete"));
    }
}
