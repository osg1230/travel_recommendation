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

    // 주로 출발하는 곳(선택). 일정 입력 화면의 출발지 기본값으로만 쓴다.
    @Nationalized
    @Column(name = "home_area", length = 60)
    private String homeArea;

    public User(String loginId, String passwordHash, String nickname) {
        this.loginId = loginId.strip();
        this.passwordHash = passwordHash;
        this.nickname = nickname.strip();
    }

    public void changeNickname(String nickname) {
        this.nickname = nickname.strip();
    }

    /** 빈 값은 '정하지 않음'으로 보고 NULL로 저장한다. */
    public void changeHomeArea(String homeArea) {
        this.homeArea = homeArea == null || homeArea.isBlank() ? null : homeArea.strip();
    }

    public void changePasswordHash(String passwordHash) {
        this.passwordHash = passwordHash;
    }

    // 관리자는 정상·이용 제한만 바꾼다. 탈퇴 처리와 탈퇴 시각은 이 기능에서 다루지 않는다.
    public void changeStatus(UserStatus status) {
        if (status != UserStatus.ACTIVE && status != UserStatus.RESTRICTED) {
            throw new IllegalArgumentException("Unsupported status change");
        }
        this.status = status;
    }

    /** 회원 본인 탈퇴. 정의서 CHECK에 맞춰 상태와 탈퇴 시각을 함께 바꾸고 닉네임은 공통 익명 이름으로 바꾼다. */
    public void withdraw(String anonymousNickname, String unusablePasswordHash) {
        this.status = UserStatus.WITHDRAWN;
        this.withdrawnAt = LocalDateTime.now(ZoneOffset.UTC);
        this.nickname = anonymousNickname;
        this.passwordHash = unusablePasswordHash;
        // 출발지는 개인 데이터라 탈퇴하면 남기지 않는다.
        this.homeArea = null;
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
