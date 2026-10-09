package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.service.KoreaTime;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.*;

/** 운영 처리 이력. 회원 상태 변경처럼 관리자가 한 처리를 남긴다. 이메일·이름·입력 원문은 기록하지 않는다. */
@Entity
@Table(name="operation_logs", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class OperationLog {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="log_id") private Long id;
    @Column(name="actor_user_id") private Long actorUserId;
    @Column(name="actor_kind", nullable=false, length=10) private String actorKind;
    @Column(name="target_type", nullable=false, length=20) private String targetType;
    // 대상이 삭제돼도 기록을 남기려고 FK 없이 식별자만 보관한다.
    @Column(name="target_id", nullable=false) private Long targetId;
    @Column(name="action_code", nullable=false, length=50) private String actionCode;
    @Column(name="before_status", length=32) private String beforeStatus;
    @Column(name="after_status", length=32) private String afterStatus;
    @Nationalized @Column(nullable=false, length=1000) private String reason;
    @Column(nullable=false, length=10) private String outcome;
    @Column(name="correlation_id", columnDefinition="uniqueidentifier") private java.util.UUID correlationId;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;

    /** 관리자가 회원 상태를 바꾼 성공 기록. 상태 변경과 같은 트랜잭션에서 저장한다. */
    public static OperationLog memberStatusChanged(Long adminUserId, Long memberUserId,
            UserStatus before, UserStatus after, String reason) {
        var log=new OperationLog();
        log.actorUserId=adminUserId; log.actorKind="ADMIN";
        log.targetType="USER"; log.targetId=memberUserId;
        log.actionCode="MEMBER_STATUS_CHANGE";
        log.beforeStatus=before.name(); log.afterStatus=after.name();
        log.reason=reason; log.outcome="SUCCESS";
        log.createdAt=KoreaTime.now();
        return log;
    }
}
