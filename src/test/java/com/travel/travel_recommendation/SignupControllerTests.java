package com.travel.travel_recommendation;

import com.travel.travel_recommendation.config.SecurityConfig;
import com.travel.travel_recommendation.controller.SignupController;
import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.service.DuplicateUserException;
import com.travel.travel_recommendation.service.KakaoMapClient;
import com.travel.travel_recommendation.service.RecommendationException;
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
import org.springframework.transaction.CannotCreateTransactionException;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SignupController.class)
@Import(SecurityConfig.class)
@ActiveProfiles("sqlserver-windows")
class SignupControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean UserService users;
    @MockitoBean KakaoMapClient maps;

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
    void homeAreaIsCheckedOnMapOnlyWhenTyped() throws Exception {
        // 비우면 지도를 찾지 않고 가입한다.
        mvc.perform(validRequest().with(csrf()).param("homeArea", " ")).andExpect(redirectedUrl("/signup/complete"));
        verifyNoInteractions(maps);
        // 찾을 수 있는 곳이면 가입한다.
        mvc.perform(validRequest().with(csrf()).param("homeArea", "서울 송파구")).andExpect(redirectedUrl("/signup/complete"));
        verify(maps).findOrigin("서울 송파구");
        verify(users, times(2)).signup(any());
    }

    @Test
    void unknownHomeAreaAsksToRetypeWithoutSigningUp() throws Exception {
        doThrow(new RecommendationException("MAP_ORIGIN", "'없는동네' 위치를 찾지 못했어요. 시·구·동 이름이나 역 이름으로 다시 적어 주세요."))
                .when(maps).findOrigin("없는동네");
        mvc.perform(validRequest().with(csrf()).param("homeArea", "없는동네")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("signupForm", "homeArea"))
                .andExpect(content().string(containsString("위치를 찾지 못했어요")))
                .andExpect(content().string(containsString("value=\"없는동네\"")));
        // 지도 서비스 문제면 비우고 가입하라고 안내하고 내부 메시지는 보이지 않는다.
        doThrow(new RecommendationException("MAP_TIMEOUT", "private map details")).when(maps).findOrigin("대전역");
        mvc.perform(validRequest().with(csrf()).param("homeArea", "대전역")).andExpect(status().isOk())
                .andExpect(model().attributeHasFieldErrors("signupForm", "homeArea"))
                .andExpect(content().string(containsString("비우고 가입한 뒤")))
                .andExpect(content().string(not(containsString("private map details"))));
        verifyNoInteractions(users);
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
        // DB 연결을 못 해 트랜잭션을 시작하지 못해도 Whitelabel 화면 대신 같은 안내를 보인다.
        doThrow(new CannotCreateTransactionException("private transaction details")).when(users).signup(any());
        mvc.perform(validRequest().with(csrf())).andExpect(status().isOk())
                .andExpect(content().string(containsString("잠시 후 다시 시도")))
                .andExpect(content().string(not(containsString("private transaction details"))));
    }

    @Test
    void loggedInMemberIsSentToStartPage() throws Exception {
        mvc.perform(get("/signup").with(user("test_user"))).andExpect(redirectedUrl("/"));
        mvc.perform(validRequest().with(user("test_user")).with(csrf())).andExpect(redirectedUrl("/"));
        mvc.perform(get("/signup").with(user("boss").roles("ADMIN"))).andExpect(redirectedUrl("/admin"));
        verifyNoInteractions(users);
    }

    @Test
    void formExplainsWhichFieldsAreOptional() throws Exception {
        mvc.perform(get("/signup")).andExpect(content().string(containsString("주로 출발하는 곳 외에는 모두 필수예요")))
                .andExpect(content().string(not(containsString("모든 항목은 필수입니다"))));
    }

    @Test
    void successPageRequiresSuccessfulSignupFlash() throws Exception {
        mvc.perform(get("/signup/complete")).andExpect(redirectedUrl("/signup"));
        mvc.perform(get("/signup/complete").flashAttr("signupComplete", true))
                .andExpect(status().isOk()).andExpect(view().name("signup-complete"));
    }
}
