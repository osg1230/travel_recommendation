package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.SignupForm;
import com.travel.travel_recommendation.entity.UserRole;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import com.travel.travel_recommendation.service.DuplicateUserException;
import com.travel.travel_recommendation.service.UserService;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

// 실제 SQL Server에서 검증하되 모든 테스트 데이터는 트랜잭션 종료 시 롤백한다.
@EnabledIfEnvironmentVariable(named = "RUN_SQLSERVER_TESTS", matches = "true")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
@AutoConfigureMockMvc
@Transactional
class SignupIntegrationTests {
    @Autowired UserService userService;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired MockMvc mvc;

    private SignupForm form() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        SignupForm form = new SignupForm();
        form.setLoginId("test_" + suffix);
        form.setNickname("여행_" + suffix);
        form.setPassword("Sample123!");
        form.setPasswordConfirm("Sample123!");
        return form;
    }

    @Test
    void httpSignupStoresHashAndCannotSetAdminRole() throws Exception {
        SignupForm form = form();
        mvc.perform(post("/signup").with(csrf()).param("loginId", " " + form.getLoginId() + " ")
                        .param("nickname", form.getNickname()).param("password", form.getPassword())
                        .param("passwordConfirm", form.getPasswordConfirm()).param("role", "ADMIN").param("status", "RESTRICTED")
                        .param("homeArea", " 서울 송파구 "))
                .andExpect(redirectedUrl("/signup/complete"));
        var saved = users.findByLoginId(form.getLoginId()).orElseThrow();
        // 주로 출발하는 곳은 선택 입력이며 앞뒤 공백을 지워 저장한다.
        assertThat(saved.getHomeArea()).isEqualTo("서울 송파구");
        assertThat(saved.getPasswordHash()).isNotEqualTo(form.getPassword()).startsWith("$2");
        assertThat(encoder.matches(form.getPassword(), saved.getPasswordHash())).isTrue();
        assertThat(saved.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(saved.getStatus()).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void duplicateLoginIsRejectedUsingDatabaseComparison() {
        SignupForm first = form();
        userService.signup(first);
        SignupForm duplicate = form();
        duplicate.setLoginId(first.getLoginId().toUpperCase(java.util.Locale.ROOT));
        assertThatThrownBy(() -> userService.signup(duplicate)).isInstanceOf(DuplicateUserException.class)
                .hasMessage("이미 사용 중인 아이디입니다.");
    }

    @Test
    void duplicateNicknameIsRejected() {
        SignupForm first = form();
        userService.signup(first);
        SignupForm duplicate = form();
        duplicate.setNickname(first.getNickname());
        assertThatThrownBy(() -> userService.signup(duplicate)).isInstanceOf(DuplicateUserException.class)
                .hasMessage("이미 사용 중인 닉네임입니다.");
    }

    @Test
    void directServiceCallAlsoValidatesInput() {
        SignupForm invalid = form();
        invalid.setPasswordConfirm("different");
        assertThatThrownBy(() -> userService.signup(invalid)).isInstanceOf(ConstraintViolationException.class);
        assertThat(users.existsByLoginId(invalid.getLoginId())).isFalse();
    }
}
