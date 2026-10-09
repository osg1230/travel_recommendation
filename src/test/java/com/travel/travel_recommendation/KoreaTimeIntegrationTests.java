package com.travel.travel_recommendation;

import com.travel.travel_recommendation.controller.TimeText;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.UserRepository;
import com.travel.travel_recommendation.service.KoreaTime;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

// 실제 DB 기본값과 JPA 저장값을 비교한다. 테스트에서 만든 회원은 모두 롤백된다.
@EnabledIfEnvironmentVariable(named = "RUN_SQLSERVER_TESTS", matches = "true")
@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
@Transactional
class KoreaTimeIntegrationTests {
    @Autowired JdbcTemplate jdbc;
    @Autowired UserRepository users;
    @Autowired TimeText timeText;

    @Test void databaseAndJavaUseTheSameKoreanClock() {
        var before = KoreaTime.now();
        var databaseNow = jdbc.queryForObject("SELECT SYSDATETIME()", LocalDateTime.class);
        var after = KoreaTime.now();
        // 왕복 시간과 DB datetime2 정밀도를 감안하되 9시간 차이는 허용하지 않는다.
        assertThat(databaseNow).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        assertThat(jdbc.queryForObject("SELECT DATEPART(TZOFFSET, SYSDATETIMEOFFSET())", Integer.class)).isEqualTo(540);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.default_constraints WHERE definition LIKE '%SYSUTCDATETIME%'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sys.default_constraints WHERE definition LIKE '%SYSDATETIME%'", Integer.class)).isEqualTo(28);
    }

    @Test void javaAndSqlDefaultTimestampsRoundTripWithoutAnExtraConversion() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 20);
        var before = KoreaTime.now();
        var user = users.saveAndFlush(new User("kst_java_" + suffix, "test-hash", "자바_" + suffix));
        var javaStored = jdbc.queryForObject("SELECT created_at FROM dbo.users WHERE user_id=?", LocalDateTime.class, user.getId());
        // 생성 시각을 생략해서 DB DEFAULT가 직접 넣은 값도 함께 확인한다.
        var sqlStored = jdbc.queryForObject("""
            INSERT INTO dbo.users(login_id, password_hash, nickname)
            OUTPUT inserted.created_at VALUES (?, ?, ?)
            """, LocalDateTime.class, "kst_sql_" + suffix, "test-hash", "SQL_" + suffix);
        var after = KoreaTime.now();
        assertThat(javaStored).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        assertThat(sqlStored).isBetween(before.minusSeconds(2), after.plusSeconds(2));
        assertThat(Duration.between(user.getCreatedAt(), javaStored).abs()).isLessThan(Duration.ofMillis(1));
        assertThat(timeText.seconds(javaStored)).isEqualTo(javaStored.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        assertThat(timeText.seconds(sqlStored)).isEqualTo(sqlStored.format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
    }
}
