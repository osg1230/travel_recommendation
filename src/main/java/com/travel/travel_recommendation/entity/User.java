package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Nationalized;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "users", schema = "dbo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "user_id")
    private Long id;

    @Column(name = "login_id", nullable = false, length = 50)
    private String loginId;

    // 평문 비밀번호가 아니라 Service에서 만든 해시만 전달받는다.
    @Column(name = "password_hash", nullable = false, length = 255)
    private String passwordHash;

    @Nationalized
    @Column(name = "nickname", nullable = false, length = 50)
    private String nickname;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 20)
    private UserRole role = UserRole.MEMBER;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(name = "created_at", nullable = false, updatable = false, columnDefinition = "datetime2(3)")
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime2(3)")
    private LocalDateTime updatedAt;

    @Column(name = "withdrawn_at", columnDefinition = "datetime2(3)")
    private LocalDateTime withdrawnAt;

    public User(String loginId, String passwordHash, String nickname) {
        this.loginId = loginId.strip();
        this.passwordHash = passwordHash;
        this.nickname = nickname.strip();
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname.strip();
    }

    @PrePersist
    private void recordCreationTime() {
        // DB 기본값과 동일하게 UTC로 저장한다. 화면 표시는 이후 KST로 변환한다.
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void recordUpdateTime() {
        updatedAt = LocalDateTime.now(ZoneOffset.UTC);
    }
}
