package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import java.io.Serializable;
import lombok.*;

/** 평가와 태그 연결. 슬롯 1~3으로 평가당 최대 3개를 DB에서도 제한한다. */
@Entity
@Table(name="place_rating_tags", schema="dbo")
@IdClass(PlaceRatingTag.Key.class)
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PlaceRatingTag {
    @Id @Column(name="rating_id") private Long ratingId;
    @Id @Column(name="tag_id") private Long tagId;
    @Column(name="slot_no", nullable=false, columnDefinition="tinyint") private short slot;

    public PlaceRatingTag(Long ratingId, Long tagId, int slot) { this.ratingId=ratingId; this.tagId=tagId; this.slot=(short)slot; }

    @EqualsAndHashCode @NoArgsConstructor @AllArgsConstructor
    public static class Key implements Serializable { private Long ratingId; private Long tagId; }
}
