package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import java.time.*;

/** 회원이 등록한 장소 즐겨찾기. 여행지·관광지·숙소만 등록한다. */
@Entity
@Table(name="favorites", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Favorite {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="favorite_id") private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="place_id", nullable=false) private Long placeId;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;

    public Favorite(Long userId, Long placeId) {
        this.userId=userId; this.placeId=placeId; createdAt=LocalDateTime.now(ZoneOffset.UTC);
    }
}
