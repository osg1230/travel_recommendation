package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.entity.UserRole;
import com.travel.travel_recommendation.entity.UserStatus;
import com.travel.travel_recommendation.repository.UserRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

// SQL Server가 준비된 PC에서 명시적으로 실행한다. 각 테스트 데이터는 롤백된다.
@EnabledIfEnvironmentVariable(named = "RUN_SQLSERVER_TESTS", matches = "true")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
@Transactional
class UserRepositoryIntegrationTests {
    @Autowired UserRepository users;
    @Autowired EntityManager entityManager;

    private User newUser(String nickname) {
        // 인증 테스트가 아니므로 실제 개인정보나 비밀번호를 사용하지 않는다.
        return new User("test_" + UUID.randomUUID(), "test-hash-not-for-login", nickname);
    }

    @Test
    void savesAndReadsKoreanNicknameAndUpdatesTimestamp() {
        String nickname = "여행_" + UUID.randomUUID();
        User user = users.saveAndFlush(newUser(nickname));
        Long id = user.getId();
        entityManager.clear();
        User stored = users.findByLoginId(user.getLoginId()).orElseThrow();
        assertThat(stored.getId()).isEqualTo(id);
        assertThat(stored.getNickname()).isEqualTo(nickname);
        assertThat(stored.getRole()).isEqualTo(UserRole.MEMBER);
        assertThat(stored.getStatus()).isEqualTo(UserStatus.ACTIVE);
        assertThat(stored.getCreatedAt()).isNotNull();
        var createdAt = stored.getCreatedAt();
        stored.changeNickname("변경_" + UUID.randomUUID());
        users.flush();
        entityManager.clear();
        User updated = users.findById(id).orElseThrow();
        assertThat(updated.getCreatedAt()).isEqualTo(createdAt);
        assertThat(updated.getUpdatedAt()).isAfterOrEqualTo(createdAt);
        assertThat(users.existsByNicknameAndStatusNot(updated.getNickname(), UserStatus.WITHDRAWN)).isTrue();
    }

    @Test
    void rejectsDuplicateLoginId() {
        User first = users.saveAndFlush(newUser("첫회원_" + UUID.randomUUID()));
        assertThatThrownBy(() -> users.saveAndFlush(new User(first.getLoginId(), "test-hash", "다른_" + UUID.randomUUID())))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateNicknameForCurrentMembers() {
        String nickname = "동일_" + UUID.randomUUID();
        users.saveAndFlush(newUser(nickname));
        assertThatThrownBy(() -> users.saveAndFlush(newUser(nickname)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void withdrawnMembersDoNotReserveNickname() {
        User first = users.saveAndFlush(newUser("탈퇴_" + UUID.randomUUID()));
        String nickname = first.getNickname();
        entityManager.createNativeQuery("UPDATE dbo.users SET status='WITHDRAWN', withdrawn_at=SYSUTCDATETIME() WHERE user_id=:id")
                .setParameter("id", first.getId()).executeUpdate();
        entityManager.clear();
        assertThat(users.existsByNicknameAndStatusNot(nickname, UserStatus.WITHDRAWN)).isFalse();
        users.saveAndFlush(newUser(nickname));
    }
}
