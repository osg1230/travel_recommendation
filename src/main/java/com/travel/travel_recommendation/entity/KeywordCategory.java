package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Nationalized;

@Entity
@Table(name = "keyword_categories", schema = "dbo")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class KeywordCategory {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "category_id") private Long id;
    @Column(name = "category_code", nullable = false, length = 40) private String code;
    @Nationalized @Column(name = "category_name", nullable = false, length = 50) private String name;
    @Column(name = "sort_order", nullable = false) private int sortOrder;
    @Column(name = "is_active", nullable = false) private boolean active;
    @Column(name = "created_at", nullable = false, columnDefinition = "datetime2(3)") private LocalDateTime createdAt;
    @Column(name = "updated_at", nullable = false, columnDefinition = "datetime2(3)") private LocalDateTime updatedAt;
}
