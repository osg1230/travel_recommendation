package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.service.KoreaTime;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Nationalized;
import java.time.*;

/** 회원이 저장한 여행 일정의 기본정보. 세부 항목은 ItineraryItem에 저장한다. */
// 바뀐 컬럼만 UPDATE한다. 잠금 없이 읽은 엔티티를 고쳐도 다른 요청이 그사이 바꾼 컬럼(예: 장소의 가격·사진 details_json)을 덮어쓰지 않게 한다.
@Entity
@DynamicUpdate
@Table(name="itineraries", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Itinerary {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="itinerary_id") private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="request_id") private Long requestId;
    @Column(name="destination_place_id", nullable=false) private Long destinationPlaceId;
    @Nationalized @Column(nullable=false, length=150) private String title;
    @Column(name="start_date", nullable=false) private LocalDate startDate;
    @Column(name="end_date", nullable=false) private LocalDate endDate;
    @Column(nullable=false, length=20) private String transportation;
    @Nationalized @Column(name="conditions_snapshot_json", nullable=false, columnDefinition="nvarchar(max)") private String conditionsSnapshotJson;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime updatedAt;
    // ROWVERSION은 SQL Server가 생성한다. 저장·삭제는 본인 확인 후 한 트랜잭션에서 처리한다.
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.BINARY)
    @Column(name="row_version", insertable=false, updatable=false, columnDefinition="rowversion") private byte[] rowVersion;

    public Itinerary(Long userId, Long requestId, Long destinationPlaceId, String title,
                     LocalDate startDate, LocalDate endDate, String transportation, String conditionsSnapshotJson) {
        this.userId=userId; this.requestId=requestId; this.destinationPlaceId=destinationPlaceId;
        this.title=title; this.startDate=startDate; this.endDate=endDate;
        this.transportation=transportation; this.conditionsSnapshotJson=conditionsSnapshotJson;
        createdAt=updatedAt=KoreaTime.now();
    }
    /** 사용자가 적용한 수정안으로 기간·이동수단·조건 스냅샷을 바꾼다. 제목은 유지한다. */
    public void applyRevision(Long requestId, LocalDate startDate, LocalDate endDate, String transportation, String conditionsSnapshotJson) {
        this.requestId=requestId; this.startDate=startDate; this.endDate=endDate;
        this.transportation=transportation; this.conditionsSnapshotJson=conditionsSnapshotJson;
        updatedAt=KoreaTime.now();
    }
    /** 같은 내용으로 다시 만든 수정용 초안을 연결한다. 일정 내용은 그대로라 버전(updatedAt)은 바꾸지 않는다. */
    public void relinkDraft(String conditionsSnapshotJson) {
        this.conditionsSnapshotJson=conditionsSnapshotJson;
    }
    /** 제목만 바꾼다. 시각·장소·순서는 수정안 적용(applyRevision)으로만 바꾼다. */
    public void rename(String title) {
        this.title=title; updatedAt=KoreaTime.now();
    }
}
