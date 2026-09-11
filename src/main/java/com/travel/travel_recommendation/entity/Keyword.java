package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "keywords", schema = "dbo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Keyword {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "keyword_id") private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false) private KeywordCategory category;
    @Column(name = "keyword_code", nullable = false, length = 50) private String code;
    @Nationalized @Column(name = "keyword_name", nullable = false, length = 80) private String name;
    @Nationalized @Column(name = "value_json", nullable = false, columnDefinition = "nvarchar(max)") private String valueJson;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime2(3)") private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime2(3)") private LocalDateTime updatedAt;
}
