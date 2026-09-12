package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.time.*;

@Entity
@Table(name="recommendation_candidates", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class RecommendationCandidate {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="candidate_id") private Long id;
    @Column(name="request_id", nullable=false) private Long requestId;
    @Column(name="place_id", nullable=false) private Long placeId;
    @Column(name="rank_no", nullable=false, columnDefinition="tinyint") private short rank;
    @Nationalized @Column(name="recommendation_reason", nullable=false, length=2000) private String reason;
    @Nationalized @Column(name="place_snapshot_json", nullable=false, columnDefinition="nvarchar(max)") private String snapshot;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    public RecommendationCandidate(Long requestId, Long placeId, int rank, String reason, String snapshot) {
        this.requestId=requestId; this.placeId=placeId; this.rank=(short)rank;
        this.reason=reason; this.snapshot=snapshot; createdAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
