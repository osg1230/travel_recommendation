package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.service.KoreaTime;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Nationalized;

// 바뀐 컬럼만 UPDATE한다. 잠금 없이 읽은 엔티티를 고쳐도 다른 요청이 그사이 바꾼 컬럼(예: 장소의 가격·사진 details_json)을 덮어쓰지 않게 한다.
@Entity
@DynamicUpdate
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

    /** 관리자가 추가하는 키워드. 값은 초기 데이터처럼 코드를 담은 JSON({"value":"코드"})으로 만든다. */
    public Keyword(KeywordCategory category, String code, String name, int sortOrder) {
        this.category = category;
        this.code = code;
        this.name = name;
        // 코드는 서비스에서 영문 대문자·숫자·밑줄만 허용하므로 따옴표 처리 없이 JSON에 넣어도 된다.
        this.valueJson = "{\"value\":\"" + code + "\"}";
        this.sortOrder = sortOrder;
        this.active = true;
    }

    // 코드와 값은 조건 해석에 쓰이므로 바꾸지 않고 이름·표시 순서만 고친다.
    public void change(String name, int sortOrder) {
        this.name = name;
        this.sortOrder = sortOrder;
    }

    // 저장된 추천 요청이 keyword_id를 참조하므로 지우지 않고 사용 여부만 바꾼다.
    public void changeActive(boolean active) {
        this.active = active;
    }

    @PrePersist
    private void recordCreationTime() {
        LocalDateTime now = KoreaTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    private void recordUpdateTime() {
        updatedAt = KoreaTime.now();
    }
}
