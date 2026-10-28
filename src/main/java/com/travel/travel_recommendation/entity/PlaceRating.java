package com.travel.travel_recommendation.entity;

import com.travel.travel_recommendation.service.KoreaTime;
import jakarta.persistence.*;
import lombok.*;
import java.time.*;
import org.hibernate.annotations.Nationalized;

/** 회원이 장소에 남긴 별점 평가(SFR-10). 회원·장소당 한 건이며 제목·본문은 없고 선택 입력 한줄평만 있다(SQL 018). */
@Entity
@Table(name="place_ratings", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class PlaceRating {
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="rating_id") private Long id;
    @Column(name="user_id", nullable=false) private Long userId;
    @Column(name="place_id", nullable=false) private Long placeId;
    @Column(nullable=false, columnDefinition="tinyint") private short score;
    /** 한줄평. 서비스에서 앞뒤 공백을 지우고 비어 있으면 null로 둔다. */
    @Nationalized @Column(length=200) private String comment;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime updatedAt;

    public PlaceRating(Long userId, Long placeId, int score, String comment) {
        this.userId=userId; this.placeId=placeId; this.score=(short)score; this.comment=comment;
        createdAt=updatedAt=KoreaTime.now();
    }
    /** 같은 장소를 다시 평가하면 새 행 대신 별점과 한줄평만 바꾼다. 태그는 서비스에서 교체한다. */
    public void rescore(int score, String comment) { this.score=(short)score; this.comment=comment; updatedAt=KoreaTime.now(); }
}
