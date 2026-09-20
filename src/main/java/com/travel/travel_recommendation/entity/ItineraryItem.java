package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.*;

/** 저장한 일정의 세부 항목. 일차·순서와 방문 시각을 그대로 보관한다. */
@Entity
@Table(name="itinerary_items", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class ItineraryItem {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="item_id") private Long id;
    @Column(name="itinerary_id", nullable=false) private Long itineraryId;
    @Column(name="day_no", nullable=false) private short dayNo;
    @Column(name="sequence_no", nullable=false) private short sequenceNo;
    @Column(name="item_type", nullable=false, length=20) private String itemType;
    @Column(name="place_id") private Long placeId;
    @Column(name="start_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime startAt;
    @Column(name="end_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime endAt;
    @Nationalized @Column(nullable=false, length=1000) private String activity;
    @Column(name="travel_minutes") private Integer travelMinutes;
    @Nationalized @Column(name="place_snapshot_json", columnDefinition="nvarchar(max)") private String placeSnapshotJson;
    @Nationalized @Column(name="transport_json", columnDefinition="nvarchar(max)") private String transportJson;
    @Nationalized @Column(name="food_guide_json", columnDefinition="nvarchar(max)") private String foodGuideJson;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime updatedAt;

    public ItineraryItem(Long itineraryId, int dayNo, int sequenceNo, String itemType, Long placeId,
                         LocalDateTime startAt, LocalDateTime endAt, String activity,
                         Integer travelMinutes, String placeSnapshotJson) {
        this.itineraryId=itineraryId; this.dayNo=(short)dayNo; this.sequenceNo=(short)sequenceNo;
        this.itemType=itemType; this.placeId=placeId; this.startAt=startAt; this.endAt=endAt;
        this.activity=activity; this.travelMinutes=travelMinutes; this.placeSnapshotJson=placeSnapshotJson;
        createdAt=updatedAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
