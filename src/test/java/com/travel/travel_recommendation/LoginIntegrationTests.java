package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.response.SecurityMockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS", matches="true")
@SpringBootTest(properties="spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
@AutoConfigureMockMvc
@Transactional
class LoginIntegrationTests {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired EntityManager em;

    private User member() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        return users.saveAndFlush(new User("test_" + suffix, encoder.encode("Travel123!"), "여행_" + suffix));
    }

    @Test
    void loginRotatesSessionErasesCredentialsAndLogoutInvalidatesIt() throws Exception {
        var user = member();
        MockHttpSession before = new MockHttpSession();
        String oldId = before.getId();
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).session(before)
                .param("loginId", " " + user.getLoginId().toUpperCase(java.util.Locale.ROOT) + " ")
                .param("password", "Travel123!"))
                .andExpect(redirectedUrl("/")).andExpect(authenticated().withUsername(user.getLoginId())).andReturn();
        var session = (MockHttpSession) result.getRequest().getSession(false);
        assertThat(session.getId()).isNotEqualTo(oldId);
        var context = (SecurityContext) session.getAttribute("SPRING_SECURITY_CONTEXT");
        assertThat(context.getAuthentication().getCredentials()).isNull();
        assertThat(((UserDetails)context.getAuthentication().getPrincipal()).getPassword()).isNull();
        mvc.perform(get("/").session(session)).andExpect(content().string(containsString("로그아웃")));
        mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/"));
        mvc.perform(get("/admin").session(session)).andExpect(status().isForbidden());
        mvc.perform(post("/logout").with(csrf()).session(session)).andExpect(redirectedUrl("/?logout"))
                .andExpect(unauthenticated());
        assertThat(session.isInvalid()).isTrue();
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/login"));
    }

    @Test
    void loginFromDraftReturnsToDraftButIgnoresOtherAddresses() throws Exception {
        // 일정 초안의 로그인 링크로 왔다면 로그인 뒤 그 초안으로 돌아간다. 외부·다른 주소는 무시하고 홈으로 보낸다.
        var user = member();
        String draft = "/travel/recommendations/itineraries/" + UUID.randomUUID();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/login").param("next", draft).session(session)).andExpect(status().isOk());
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(session)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl(draft));

        MockHttpSession other = new MockHttpSession();
        mvc.perform(get("/login").param("next", "https://example.com/travel/recommendations/itineraries/x").session(other));
        mvc.perform(get("/login").param("next", "/my/account").session(other));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(other)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/"));
    }

    @Autowired com.travel.travel_recommendation.service.RecommendationJobService recommendations;

    @Test
    void loginMovesGuestRecommendationsAndShowsNoticeOnce() throws Exception {
        var user = member();
        MockHttpSession session = new MockHttpSession();
        String token = UUID.randomUUID().toString();
        session.setAttribute("recommendationGuestToken", token);
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var conditions = new com.travel.travel_recommendation.dto.ConfirmedTravelConditions("KEYWORD", "", "강릉", null, null, 2, null, 2, true, null, java.util.List.of(), "");
        UUID job = recommendations.create(conditions, null, hash);
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).session(session)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/")).andReturn();
        var loggedIn = (MockHttpSession) result.getRequest().getSession(false);
        mvc.perform(get("/").session(loggedIn)).andExpect(content().string(containsString("로그인 전에 받은 추천 1건을 추천 이력에 옮겼어요")));
        mvc.perform(get("/").session(loggedIn)).andExpect(content().string(org.hamcrest.Matchers.not(containsString("로그인 전에 받은 추천"))));
        em.flush(); em.clear();
        assertThat(recommendations.view(job, user.getLoginId(), null).status()).isNotBlank();
    }

    @Test
    void adminGetsItsOwnStartPage() throws Exception {
        var user = member();
        em.createNativeQuery("UPDATE dbo.users SET role='ADMIN' WHERE user_id=:id").setParameter("id", user.getId()).executeUpdate();
        em.clear();
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Travel123!")).andExpect(redirectedUrl("/admin"))
                .andExpect(authenticated().withRoles("ADMIN")).andReturn();
        var session = (MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/admin").session(session)).andExpect(status().isOk()).andExpect(view().name("admin/index"));
        mvc.perform(get("/login").session(session)).andExpect(redirectedUrl("/admin"));
    }

    @Test
    void wrongUnknownRestrictedAndWithdrawnUseSameFailureRedirect() throws Exception {
        var user = member();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Wrong123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", "missing_" + UUID.randomUUID().toString().replace("-", ""))
                .param("password", "Travel123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        for (String state : new String[]{"RESTRICTED", "WITHDRAWN"}) {
            em.createNativeQuery("UPDATE dbo.users SET status=:state, withdrawn_at=CASE WHEN :state='WITHDRAWN' THEN SYSUTCDATETIME() ELSE NULL END WHERE user_id=:id")
                    .setParameter("state", state).setParameter("id", user.getId()).executeUpdate();
            em.clear();
            mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                    .param("password", "Travel123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        }
    }

    @Test
    void csrfIsRequiredForLoginAndLogoutAndGetDoesNotLogout() throws Exception {
        mvc.perform(post("/login").servletPath("/login")).andExpect(status().isForbidden());
        mvc.perform(post("/logout")).andExpect(status().isForbidden());
        var user = member();
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Travel123!")).andReturn();
        var session = (MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/logout").session(session)).andExpect(authenticated());
        assertThat(session.isInvalid()).isFalse();
        mvc.perform(post("/signup").session(session).with(csrf())).andExpect(status().isForbidden());
    }

    @Test
    void invalidInputIsRejectedWithoutServerError() throws Exception {
        for (String password : new String[]{"", "x".repeat(200), "가".repeat(64)}) {
            mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", "test_user")
                    .param("password", password)).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        }
        mvc.perform(get("/login")).andExpect(status().isOk()).andExpect(content().string(containsString("name=\"_csrf\"")));
    }
}
