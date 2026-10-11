package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserRole;
import com.travel.travel_recommendation.repository.UserRepository;
import com.travel.travel_recommendation.service.KakaoMapClient;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS", matches="true")
@SpringBootTest(properties="spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
@AutoConfigureMockMvc
@Transactional
class ProfileIntegrationTests {
    @Autowired MockMvc mvc;
    // 출발지 확인은 외부 지도 API라 가짜로 두고 저장만 검증한다.
    @MockitoBean KakaoMapClient maps;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager em;

    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(new User("test_"+suffix, encoder.encode("Travel123!"), "여행_"+suffix));
    }
    private MockHttpServletRequestBuilder update(User member, String nickname) {
        return post("/account").with(user(member.getLoginId())).with(csrf())
                .param("nickname", nickname).param("currentPassword", "Travel123!");
    }

    @Test
    void onlyReadsAndUpdatesSessionOwnerDespiteInjectedFields() throws Exception {
        var owner=member(); var other=member();
        String originalHash=owner.getPasswordHash();
        mvc.perform(get("/account").with(user(owner.getLoginId())).param("loginId", other.getLoginId()))
                .andExpect(status().isOk()).andExpect(content().string(containsString(owner.getNickname())))
                .andExpect(content().string(not(containsString(other.getNickname()))))
                .andExpect(content().string(not(containsString(originalHash))));
        mvc.perform(update(owner, " 새닉네임 ").param("id", other.getId().toString())
                .param("loginId", other.getLoginId()).param("role", "ADMIN").param("status", "WITHDRAWN"))
                .andExpect(redirectedUrl("/account")).andExpect(flash().attribute("profileUpdated", true));
        em.clear();
        var saved=users.findById(owner.getId()).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo("새닉네임");
        assertThat(saved.getPasswordHash()).isEqualTo(originalHash);
        assertThat(saved.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(users.findById(other.getId()).orElseThrow().getNickname()).isEqualTo(other.getNickname());
        mvc.perform(get("/account").with(user(owner.getLoginId())))
                .andExpect(content().string(containsString("새닉네임")));
    }

    @Test
    void wrongCurrentPasswordLeavesBothFieldsUnchangedAndNeverEchoesSecrets() throws Exception {
        var owner=member(); String before=owner.getNickname(); String hash=owner.getPasswordHash();
        mvc.perform(post("/account").with(user(owner.getLoginId())).with(csrf()).param("nickname", "바뀌면안됨")
                .param("currentPassword", "Wrong123!").param("newPassword", "NewTravel123!").param("newPasswordConfirm", "NewTravel123!"))
                .andExpect(model().attributeHasFieldErrors("profileForm", "currentPassword"))
                .andExpect(content().string(not(containsString("Wrong123!"))))
                .andExpect(content().string(not(containsString("NewTravel123!"))));
        em.clear(); var saved=users.findById(owner.getId()).orElseThrow();
        assertThat(saved.getNickname()).isEqualTo(before);
        assertThat(saved.getPasswordHash()).isEqualTo(hash);
    }

    @Test
    void duplicateNicknameIsRejectedWithoutChangingPassword() throws Exception {
        var owner=member(); var other=member(); String hash=owner.getPasswordHash();
        mvc.perform(update(owner, other.getNickname()).param("newPassword", "NewTravel123!")
                .param("newPasswordConfirm", "NewTravel123!"))
                .andExpect(model().attributeHasFieldErrors("profileForm", "nickname"));
        em.clear(); assertThat(users.findById(owner.getId()).orElseThrow().getPasswordHash()).isEqualTo(hash);
    }

    // '주로 출발하는 곳'만 바꿔도 저장되고, 비우고 저장하면 지운다. 같은 값이면 변경 없음이다.
    @Test
    void homeAreaCanBeSetClearedAndIsShownOnTheForm() throws Exception {
        var owner=member();
        mvc.perform(update(owner, owner.getNickname()).param("homeArea", "  서울 송파구 "))
                .andExpect(redirectedUrl("/account"));
        em.clear(); assertThat(users.findById(owner.getId()).orElseThrow().getHomeArea()).isEqualTo("서울 송파구");
        mvc.perform(get("/account").with(user(owner.getLoginId())))
                .andExpect(content().string(containsString("value=\"서울 송파구\"")));
        mvc.perform(update(owner, owner.getNickname()).param("homeArea", "서울 송파구"))
                .andExpect(content().string(containsString("변경할 내용이 없습니다.")));
        mvc.perform(update(owner, owner.getNickname()).param("homeArea", ""))
                .andExpect(redirectedUrl("/account"));
        em.clear(); assertThat(users.findById(owner.getId()).orElseThrow().getHomeArea()).isNull();
        mvc.perform(update(owner, owner.getNickname()).param("homeArea", "가".repeat(61)))
                .andExpect(model().attributeHasFieldErrors("profileForm", "homeArea"));
    }

    @Test
    void ownNicknameCaseChangeUsesDatabaseDuplicateRulesAndNoChangeIsReported() throws Exception {
        var owner=member();
        mvc.perform(update(owner, owner.getNickname())).andExpect(content().string(containsString("변경할 내용이 없습니다.")));
        mvc.perform(update(owner, owner.getNickname().toUpperCase(java.util.Locale.ROOT)))
                .andExpect(redirectedUrl("/account"));
    }

    @Test
    void changingPasswordLogsOutAndOnlyNewPasswordAuthenticates() throws Exception {
        var owner=member();
        var login=mvc.perform(post("/login").servletPath("/login").with(csrf())
                .param("loginId", owner.getLoginId()).param("password", "Travel123!")).andReturn();
        var session=(MockHttpSession)login.getRequest().getSession(false);
        mvc.perform(post("/account").session(session).with(csrf()).param("nickname", owner.getNickname())
                .param("currentPassword", "Travel123!").param("newPassword", "NewTravel123!")
                .param("newPasswordConfirm", "NewTravel123!"))
                .andExpect(redirectedUrl("/login?passwordChanged")).andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
        em.clear();
        assertThat(encoder.matches("NewTravel123!", users.findById(owner.getId()).orElseThrow().getPasswordHash())).isTrue();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", owner.getLoginId())
                .param("password", "Travel123!")).andExpect(redirectedUrl("/login?error"));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", owner.getLoginId())
                .param("password", "NewTravel123!")).andExpect(authenticated());
    }

    @Test
    void inactiveAccountCannotReadOrModifyProfileEvenWithOldAuthentication() throws Exception {
        var owner=member();
        em.createNativeQuery("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=:id").setParameter("id",owner.getId()).executeUpdate();
        em.clear();
        mvc.perform(get("/account").with(user(owner.getLoginId()))).andExpect(status().isForbidden());
        mvc.perform(update(owner, "변경차단")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousCsrfAndInvalidFieldsAreRejected() throws Exception {
        var owner=member();
        mvc.perform(get("/account")).andExpect(redirectedUrl("/login"));
        mvc.perform(post("/account").with(user(owner.getLoginId()))).andExpect(status().isForbidden());
        mvc.perform(update(owner, "새닉네임").param("newPassword", "NewTravel123!").param("newPasswordConfirm", "Different123!"))
                .andExpect(model().attributeHasFieldErrors("profileForm", "passwordMatching"));
        mvc.perform(update(owner, " ").param("newPassword", "weak").param("newPasswordConfirm", "weak"))
                .andExpect(model().attributeHasFieldErrors("profileForm", "nickname", "newPassword"));
    }
}
