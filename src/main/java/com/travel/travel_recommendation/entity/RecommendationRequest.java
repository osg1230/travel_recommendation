package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.math.BigDecimal;
import java.time.*;

@Entity
@Table(name="recommendation_requests", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class RecommendationRequest {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="request_id") private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="input_mode", nullable=false, length=10) private String inputMode;
    @Nationalized @Column(name="original_text", length=2000) private String originalText;
    @Nationalized @Column(name="conditions_json", nullable=false, columnDefinition="nvarchar(max)") private String conditionsJson;
    @Nationalized @Column(length=100) private String region;
    @Column(name="start_date") private LocalDate startDate;
    @Column(name="end_date") private LocalDate endDate;
    @Column(name="duration_days") private Short durationDays;
    @Column(precision=12, scale=2) private BigDecimal budget;
    @Column(name="accommodation_needed") private Boolean accommodationNeeded;
    @Nationalized @Column(name="interpretation_summary", length=1000) private String interpretationSummary;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime updatedAt;

    /** 로그인 전에 받은 비회원 추천을 옮길 때 쓴다. 이력에는 원래 추천을 요청한 시각을 남긴다. */
    public RecommendationRequest(Long userId, ConfirmedTravelConditions c, String json, LocalDateTime requestedAt) {
        this(userId, c, json);
        createdAt=updatedAt=requestedAt;
    }
    public RecommendationRequest(Long userId, ConfirmedTravelConditions c, String json) {
        this.userId=userId; inputMode=c.inputMode(); originalText=c.originalText();
        conditionsJson=json; region=c.region().isBlank()?null:c.region();
        startDate=c.startDate(); endDate=c.endDate();
        durationDays=c.durationDays()==null?null:c.durationDays().shortValue();
        budget=c.budget(); accommodationNeeded=c.accommodationNeeded();
        interpretationSummary=c.interpretationSummary();
        createdAt=updatedAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
