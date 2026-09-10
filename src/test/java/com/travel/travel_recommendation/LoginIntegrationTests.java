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
