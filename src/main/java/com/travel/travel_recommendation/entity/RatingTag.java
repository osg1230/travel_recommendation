package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;

/** 사전 정의 평가 태그. SQL로만 관리하며 화면에서 추가·수정하지 않는다. */
@Entity
@Table(name="rating_tags", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class RatingTag {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="tag_id") private Long id;
    @Column(name="tag_code", nullable=false, length=40) private String code;
    @Nationalized @Column(name="tag_name", nullable=false, length=80) private String name;
    @Column(name="sort_order", nullable=false) private int sortOrder;
    @Column(name="is_active", nullable=false) private boolean active;
}
