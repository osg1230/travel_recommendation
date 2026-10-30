package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
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
        mvc.perform(get("/admin")).andExpect(redirectedUrl("/login?required"));
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
        mvc.perform(get("/login").param("next", "/admin/members").session(other));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(other)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void protectedPageExplainsLoginAndReturnsThereAfterward() throws Exception {
        // 로그인 없이 회원 화면을 열면 이유를 알리는 로그인 화면으로 가고, 로그인하면 그 화면으로 돌아온다.
        var user = member();
        MockHttpSession session = new MockHttpSession();
        mvc.perform(get("/my/itineraries?page=2").accept(MediaType.TEXT_HTML).session(session))
                .andExpect(redirectedUrl("/login?required"));
        mvc.perform(get("/login").param("required", "").session(session))
                .andExpect(content().string(containsString("로그인이 필요하거나 로그인 시간이 지났어요")));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(session)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/my/itineraries?page=2"));
        // 화면 안에서 부르는 데이터 주소(HTML이 아닌 요청)는 돌아갈 곳으로 기억하지 않는다.
        MockHttpSession fetch = new MockHttpSession();
        mvc.perform(get("/my/itineraries/7/day-paths").param("day", "1").accept(MediaType.APPLICATION_JSON).session(fetch))
                .andExpect(redirectedUrl("/login?required"));
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(fetch)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/"));
    }

    @Test
    void sessionEndsWhenRestrictedWithdrawnOrPasswordChangedElsewhere() throws Exception {
        // 관리자가 이용 제한하면 다음 요청에서 로그아웃되고 이유를 본다(SFR-15-03).
        var user = member();
        var restricted = login(user);
        em.createNativeQuery("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=:id").setParameter("id", user.getId()).executeUpdate();
        em.clear();
        mvc.perform(get("/account").session(restricted)).andExpect(redirectedUrl("/login?restricted")).andExpect(unauthenticated());
        assertThat(restricted.isInvalid()).isTrue();
        mvc.perform(get("/login").param("restricted", "")).andExpect(content().string(containsString("이용이 제한된 계정")));

        // 다른 기기에서 비밀번호를 바꾸면 이 기기의 세션은 끝난다(SFR-01-04). CSS 같은 정적 파일은 확인하지 않는다.
        var other = member();
        var oldDevice = login(other);
        mvc.perform(get("/account").session(oldDevice)).andExpect(status().isOk());
        em.createNativeQuery("UPDATE dbo.users SET password_hash=:hash WHERE user_id=:id")
                .setParameter("hash", encoder.encode("NewTravel123!")).setParameter("id", other.getId()).executeUpdate();
        em.clear();
        mvc.perform(get("/css/app.css").session(oldDevice));
        assertThat(oldDevice.isInvalid()).isFalse();
        mvc.perform(get("/").session(oldDevice)).andExpect(redirectedUrl("/login?changed"));
        assertThat(oldDevice.isInvalid()).isTrue();

        // 탈퇴한 계정의 다른 세션도 끝난다(SFR-01-05).
        var third = member();
        var device = login(third);
        em.createNativeQuery("UPDATE dbo.users SET status='WITHDRAWN', withdrawn_at=SYSDATETIME() WHERE user_id=:id")
                .setParameter("id", third.getId()).executeUpdate();
        em.clear();
        mvc.perform(post("/logout").with(csrf()).session(device)).andExpect(redirectedUrl("/?withdrawn"));
        assertThat(device.isInvalid()).isTrue();
    }

    private MockHttpSession login(User user) throws Exception {
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf())
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(authenticated()).andReturn();
        return (MockHttpSession) result.getRequest().getSession(false);
    }

    @Autowired com.travel.travel_recommendation.service.RecommendationJobService recommendations;

    // 공용 PC에서 앞사람의 비회원 추천이 다음 사람 이력으로 옮겨지지 않도록 로그인해도 옮기지 않는다.
    @Test
    void loginDoesNotMoveGuestRecommendationsIntoHistory() throws Exception {
        var user = member();
        MockHttpSession session = new MockHttpSession();
        String token = UUID.randomUUID().toString();
        session.setAttribute("recommendationGuestToken", token);
        byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var conditions = new com.travel.travel_recommendation.dto.ConfirmedTravelConditions("KEYWORD", "", "강릉", null, null, 2, null, 2, true, null, java.util.List.of(), "");
        UUID job = recommendations.create(conditions, null, hash);
        mvc.perform(post("/login").servletPath("/login").with(csrf()).session(session)
                .param("loginId", user.getLoginId()).param("password", "Travel123!"))
                .andExpect(redirectedUrl("/"));
        em.flush(); em.clear();
        assertThat(recommendations.findOwned(job, user.getLoginId(), null, "RECOMMENDATION")).isEmpty();
        assertThat(recommendations.findOwned(job, user.getLoginId(), hash, "RECOMMENDATION")).isPresent();
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
    void restrictedIsToldOnlyWithCorrectPasswordAndOtherFailuresShareOneMessage() throws Exception {
        var user = member();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Wrong123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", "missing_" + UUID.randomUUID().toString().replace("-", ""))
                .param("password", "Travel123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        // 이용 제한 계정은 비밀번호가 맞을 때만 따로 알린다(SFR-01-02 A2). 틀리면 공통 문구다.
        em.createNativeQuery("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=:id").setParameter("id", user.getId()).executeUpdate();
        em.clear();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Wrong123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Travel123!")).andExpect(redirectedUrl("/login?restricted")).andExpect(unauthenticated());
        // 탈퇴 계정은 아이디가 있는지 드러나지 않게 공통 문구를 쓴다.
        em.createNativeQuery("UPDATE dbo.users SET status='WITHDRAWN', withdrawn_at=SYSDATETIME() WHERE user_id=:id")
                .setParameter("id", user.getId()).executeUpdate();
        em.clear();
        mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Travel123!")).andExpect(redirectedUrl("/login?error")).andExpect(unauthenticated());
    }

    @Test
    void csrfIsRequiredForLoginAndLogoutAndGetDoesNotLogout() throws Exception {
        // 토큰이 없으면 로그인·로그아웃은 되지 않지만, 403 화면 대신 다시 할 수 있는 화면으로 보낸다(UC-03 A1).
        mvc.perform(post("/login").servletPath("/login")).andExpect(redirectedUrl("/login?expired")).andExpect(unauthenticated());
        mvc.perform(post("/logout")).andExpect(redirectedUrl("/?logout"));
        var user = member();
        var result = mvc.perform(post("/login").servletPath("/login").with(csrf()).param("loginId", user.getLoginId())
                .param("password", "Travel123!")).andReturn();
        var session = (MockHttpSession)result.getRequest().getSession(false);
        mvc.perform(get("/logout").session(session)).andExpect(authenticated());
        assertThat(session.isInvalid()).isFalse();
        // 로그인한 상태의 로그아웃은 토큰이 맞아야만 한다. 로그인 상태도 그대로다.
        mvc.perform(post("/logout").session(session)).andExpect(status().isForbidden());
        assertThat(session.isInvalid()).isFalse();
        // 로그인한 상태에서 회원가입을 열면 403 대신 시작 화면으로 보낸다.
        mvc.perform(post("/signup").session(session).with(csrf())).andExpect(redirectedUrl("/"));
        mvc.perform(get("/signup").session(session)).andExpect(redirectedUrl("/"));
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
