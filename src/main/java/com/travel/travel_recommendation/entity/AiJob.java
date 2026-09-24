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
    /** 로그인 전에 이 세션에서 만든 비회원 작업을 로그인한 회원의 작업으로 옮긴다. 비회원 토큰은 지운다. */
    public void adopt(Long userId, Long requestId) {
        this.userId=userId; this.requestId=requestId; guestTokenHash=null;
    }
    public void searching() { status="SEARCHING_CANDIDATES"; startedAt=LocalDateTime.now(ZoneOffset.UTC); }
    public static AiJob itinerary(AiJob parent, String input) {
        var job=new AiJob(parent.userId,parent.guestTokenHash,parent.requestId,input,parent.expiresAt);
        job.jobType="ITINERARY";
        job.message="장소 좌표와 이동시간을 확인할 준비를 하고 있어요.";
        return job;
    }
    /** 저장한 일정에서 수정을 이어 가려고 다시 만든 추천 작업. 저장한 여행지 하나만 결과로 두고 추천 이력에는 연결하지 않는다. */
    public static AiJob restoredRecommendation(Long userId, String input, String result, LocalDateTime expiresAt) {
        var job=new AiJob(userId,null,null,input,expiresAt);
        job.completeRecommendation(result);
        job.message="저장한 일정에서 수정을 이어 가려고 여행지를 다시 불러왔어요.";
        return job;
    }
    /** 저장한 일정의 시각·장소를 그대로 옮긴 완성 초안. 새로 배치하지 않으며 이 초안에서 수정안을 만든다. */
    public static AiJob restoredItinerary(AiJob parent, String input, String result) {
        var job=itinerary(parent,input);
        job.completeSchedule(result);
        job.message="저장한 일정으로 수정할 초안을 다시 만들었어요.";
        return job;
    }
    public void generatingSchedule() {
        status="GENERATING_SCHEDULE"; startedAt=LocalDateTime.now(ZoneOffset.UTC); attemptCount++;
        message="장소 좌표와 이동시간을 확인하고 날짜별로 배치하고 있어요.";
    }
    public void validatingSchedule() { status="VALIDATING_SCHEDULE"; message="시간 충돌과 방문 순서를 검사하고 있어요."; }
    public void completeSchedule(String json) {
        completeRecommendation(json); message="이동시간을 반영한 일정 초안이 준비됐어요.";
    }
    /** 후보 조회 전의 추천 요청에 목적 시설 검색 결과를 붙인다. 사용자가 확정한 다른 조건은 바꾸지 않는다. */
    public void attachRequest(String json) {
        if(!"RECOMMENDATION".equals(jobType) || !"QUEUED".equals(status)) throw new IllegalStateException("Only queued recommendations");
        requestJson=json;
    }
    /** 처리 전의 일정 요청에 동선 주변에서 찾은 음식점 후보를 더한다. 사용자가 고른 관광지·시간 등 다른 값은 바꾸지 않는다. */
    public void attachItineraryRequest(String json) {
        if(!"ITINERARY".equals(jobType) || !"QUEUED".equals(status)) throw new IllegalStateException("Only queued itineraries");
        requestJson=json;
    }
    /** 완성된 일정 초안에 나중에 찾은 참고 정보(숙소·음식점 가격)를 붙인다. 일정 자체는 서버가 만든 그대로 둔다. */
    public void attachToResult(String json) {
        if(!"ITINERARY".equals(jobType) || !"COMPLETED".equals(status)) throw new IllegalStateException("Only completed itineraries");
        resultJson=json;
    }
    public void candidatesReady(String json) {
        status="CANDIDATES_READY"; resultJson=json;
        message="여행지 후보를 찾았어요. 확정한 조건으로 추천 순위와 이유를 받아보세요.";
    }
    public void queueRecommendation() {
        status="RECOMMENDATION_QUEUED"; errorCode=null; completedAt=null;
        startedAt=LocalDateTime.now(ZoneOffset.UTC);
        message="여행지 추천 요청을 접수했어요.";
    }
    public void ranking() {
        status="RANKING_CANDIDATES"; attemptCount++;
        message="확정한 조건에 맞춰 여행지를 비교하고 있어요.";
    }
    public void completeRecommendation(String json) {
        status="COMPLETED"; resultJson=json; errorCode=null;
        completedAt=LocalDateTime.now(ZoneOffset.UTC); message="여행지 추천이 준비됐어요.";
    }
    public void failRecommendation(String code, String safeMessage) {
        // 재시도에는 같은 후보를 사용한다. 실패한 AI 원문은 저장하지 않는다.
        status="FAILED"; errorCode=code; message=safeMessage;
        completedAt=LocalDateTime.now(ZoneOffset.UTC);
    }
    public void fail(String code, String safeMessage) {
        status="FAILED"; errorCode=code; message=safeMessage; resultJson=null;
        completedAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
