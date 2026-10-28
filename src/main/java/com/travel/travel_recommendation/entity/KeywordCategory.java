package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.service.KoreaTime;
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

    // 카테고리 코드는 조건 입력 화면의 질문과 연결돼 있어 바꾸지 않고 이름·표시 순서만 고친다.
    public void change(String name, int sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    // 소속 키워드가 있으므로 지우지 않고 사용 여부만 바꾼다.
    public void changeActive(boolean active) {
        this.active = active;
    }

    @PreUpdate
    private void recordUpdateTime() {
        updatedAt = KoreaTime.now();
    }
}
