package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.*;
import java.util.UUID;

@Entity
@Table(name="ai_jobs", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class AiJob {
    @Id @Column(name="job_id", columnDefinition="uniqueidentifier") private UUID id;
    @Column(name="user_id") private Long userId;
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.BINARY)
    @Column(name="guest_token_hash", columnDefinition="binary(32)") private byte[] guestTokenHash;
    @Column(name="request_id") private Long requestId;
    @Column(name="job_type", nullable=false, length=20) private String jobType;
    @Column(nullable=false, length=32) private String status;
    @Column(name="attempt_count", nullable=false, columnDefinition="tinyint") private short attemptCount;
    @Nationalized @Column(name="request_json", nullable=false, columnDefinition="nvarchar(max)") private String requestJson;
    @Nationalized @Column(name="result_json", columnDefinition="nvarchar(max)") private String resultJson;
    @Nationalized @Column(name="validation_errors_json", columnDefinition="nvarchar(max)") private String validationErrorsJson;
    @Column(name="error_code", length=60) private String errorCode;
    @Nationalized @Column(length=1000) private String message;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="started_at", columnDefinition="datetime2(3)") private LocalDateTime startedAt;
    @Column(name="completed_at", columnDefinition="datetime2(3)") private LocalDateTime completedAt;
    @Column(name="expires_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime expiresAt;
    // 상태 변경 시 Repository의 행 잠금을 사용한다. ROWVERSION은 SQL Server가 생성한다.
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.BINARY)
    @Column(name="row_version", insertable=false, updatable=false, columnDefinition="rowversion") private byte[] rowVersion;

    public AiJob(Long userId, byte[] guestHash, Long requestId, String input, LocalDateTime expiresAt) {
        id=UUID.randomUUID(); this.userId=userId; guestTokenHash=guestHash==null?null:guestHash.clone();
        this.requestId=requestId; requestJson=input; this.expiresAt=expiresAt;
        jobType="RECOMMENDATION"; status="QUEUED"; createdAt=LocalDateTime.now(ZoneOffset.UTC);
        message="확정 조건으로 여행지 후보를 조회할 준비를 하고 있어요.";
    }
    public void searching() { status="SEARCHING_CANDIDATES"; startedAt=LocalDateTime.now(ZoneOffset.UTC); }
    public void candidatesReady(String json) {
        status="CANDIDATES_READY"; resultJson=json;
        message="여행지 후보를 찾았어요. GPT 추천 순위와 이유 생성은 다음 단계에서 연결됩니다.";
    }
    public void fail(String code, String safeMessage) {
        status="FAILED"; errorCode=code; message=safeMessage; resultJson=null;
        completedAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
