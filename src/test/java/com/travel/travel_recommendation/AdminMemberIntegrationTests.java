package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.AdminMemberService;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 관리자 회원 검색·상태 변경과 운영 이력 기록을 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class AdminMemberIntegrationTests {
    @Autowired AdminMemberService members;
    @Autowired UserRepository users;
    @Autowired OperationLogRepository logs;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private User admin;
    private User member;

    @BeforeEach void setup() {
        admin=account(UserRole.ADMIN);
        member=account(UserRole.MEMBER);
    }
    private User account(UserRole role) {
        String suffix=UUID.randomUUID().toString().replace("-","").substring(0,20);
        var user=users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
        if(role==UserRole.ADMIN) jdbc.update("UPDATE dbo.users SET role='ADMIN' WHERE user_id=?",user.getId());
        em.flush(); em.clear();
        return users.findById(user.getId()).orElseThrow();
    }

    @Test void searchesByLoginIdAndStatus() {
        var found=members.search(member.getLoginId(),null,0,admin.getLoginId());
        assertThat(found.totalCount()).isEqualTo(1);
        assertThat(found.members().getFirst().loginId()).isEqualTo(member.getLoginId());
        assertThat(found.members().getFirst().statusName()).isEqualTo("정상");
        assertThat(members.search(member.getLoginId(),"RESTRICTED",0,admin.getLoginId()).members()).isEmpty();
        assertThatThrownBy(()->members.search(null,"UNKNOWN",0,admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
    }

    @Test void changesStatusAndRecordsReasonInSameTransaction() throws Exception {
        mvc.perform(post("/admin/members/"+member.getId()+"/status")
                .with(user(admin.getLoginId()).roles("ADMIN")).with(csrf())
                .param("status","RESTRICTED").param("reason"," 약관 위반 신고 확인 "))
            .andExpect(redirectedUrl("/admin/members/"+member.getId()));
        em.clear();
        assertThat(users.findById(member.getId()).orElseThrow().getStatus()).isEqualTo(UserStatus.RESTRICTED);
        var history=logs.findTop10ByTargetTypeAndTargetIdOrderByIdDesc("USER",member.getId());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().getActorUserId()).isEqualTo(admin.getId());
        assertThat(history.getFirst().getBeforeStatus()).isEqualTo("ACTIVE");
        assertThat(history.getFirst().getAfterStatus()).isEqualTo("RESTRICTED");
        assertThat(history.getFirst().getReason()).isEqualTo("약관 위반 신고 확인");
        assertThat(history.getFirst().getOutcome()).isEqualTo("SUCCESS");
        // 같은 상태로 다시 바꾸면 기록을 늘리지 않는다.
        assertThatThrownBy(()->members.changeStatus(member.getId(),"RESTRICTED","다시 제한",admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        em.clear();
        assertThat(logs.findTop10ByTargetTypeAndTargetIdOrderByIdDesc("USER",member.getId())).hasSize(1);
        var detail=members.detail(member.getId(),admin.getLoginId());
        assertThat(detail.history().getFirst().adminLoginId()).isEqualTo(admin.getLoginId());
        assertThat(detail.history().getFirst().afterStatus()).isEqualTo("이용 제한");
    }

    @Test void refusesAdminAccountsWithdrawnMembersAndEmptyReason() {
        assertThat(members.detail(admin.getId(),admin.getLoginId()).statusChangeable()).isFalse();
        assertThatThrownBy(()->members.changeStatus(admin.getId(),"RESTRICTED","확인",admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("관리자 계정");
        assertThatThrownBy(()->members.changeStatus(member.getId(),"RESTRICTED","  ",admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("변경 사유");
        assertThatThrownBy(()->members.changeStatus(member.getId(),"WITHDRAWN","탈퇴 처리",admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("정상과 이용 제한");
        // 탈퇴 상태는 withdrawn_at이 있어야 한다는 DB 제약에 맞춰 만든다.
        jdbc.update("UPDATE dbo.users SET status='WITHDRAWN', withdrawn_at=SYSUTCDATETIME() WHERE user_id=?",member.getId());
        em.clear();
        assertThatThrownBy(()->members.changeStatus(member.getId(),"ACTIVE","복구",admin.getLoginId()))
            .isInstanceOf(org.springframework.web.server.ResponseStatusException.class).hasMessageContaining("탈퇴한 회원");
        assertThat(logs.findTop10ByTargetTypeAndTargetIdOrderByIdDesc("USER",member.getId())).isEmpty();
    }

    @Test void refusesNonAdminAccounts() throws Exception {
        assertThatThrownBy(()->members.search(null,null,0,member.getLoginId()))
            .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        // 권한 없는 계정이 ADMIN 역할을 흉내 내도 서버에서 실제 계정 권한을 다시 확인한다.
        mvc.perform(get("/admin/members").with(user(member.getLoginId()).roles("ADMIN")))
            .andExpect(status().isForbidden());
        mvc.perform(get("/admin/members").with(user(admin.getLoginId()).roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("회원 관리")));
    }
}
