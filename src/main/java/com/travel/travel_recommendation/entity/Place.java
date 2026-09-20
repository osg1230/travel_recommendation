package com.travel.travel_recommendation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Nationalized;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name="places", schema="dbo")
@Getter
@NoArgsConstructor(access=AccessLevel.PROTECTED)
public class Place {
    public void updateDetails(String detailsJson) {
        this.detailsJson = detailsJson;
        this.updatedAt = LocalDateTime.now(java.time.ZoneOffset.UTC);
    }
    public static Place fromKakao(com.travel.travel_recommendation.dto.KakaoPlace data) {
        var place=new Place(); place.sourceProvider="KAKAO"; place.sourcePlaceId=data.sourceId();
        place.placeType=data.type(); place.region=data.region(); place.active=true; place.createdAt=data.checkedAt();
        place.refreshKakao(data); return place;
    }
    public void refreshKakao(com.travel.travel_recommendation.dto.KakaoPlace data) {
        if(!"KAKAO".equals(sourceProvider) || !sourcePlaceId.equals(data.sourceId()) || !region.equals(data.region())
                || !placeType.equals(data.type())) throw new IllegalArgumentException("Place identity mismatch");
        name=data.name(); address=data.address(); subtype=data.category();
        longitude=BigDecimal.valueOf(data.longitude()).setScale(6,java.math.RoundingMode.HALF_UP);
        latitude=BigDecimal.valueOf(data.latitude()).setScale(6,java.math.RoundingMode.HALF_UP);
        sourceUrl=data.url(); mapUrl=data.url(); verifiedAt=data.checkedAt(); updatedAt=data.checkedAt();
        description="카카오 장소 검색에서 확인한 후보예요. 영업시간·휴무·가격은 상세 페이지에서 확인해 주세요.";
    }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) @Column(name="place_id") private Long id;
    @Column(name="place_type", nullable=false, length=20) private String placeType;
    @Column(name="source_provider", nullable=false, length=40) private String sourceProvider;
    @Nationalized @Column(name="source_place_id", nullable=false, length=150) private String sourcePlaceId;
    @Nationalized @Column(name="place_name", nullable=false, length=200) private String name;
    @Nationalized @Column(nullable=false, length=100) private String region;
    @Nationalized @Column(length=300) private String address;
    @Column(precision=9, scale=6) private BigDecimal latitude;
    @Column(precision=9, scale=6) private BigDecimal longitude;
    @Nationalized @Column(length=80) private String subtype;
    @Nationalized @Column(length=2000) private String description;
    @Nationalized @Column(name="details_json", columnDefinition="nvarchar(max)") private String detailsJson;
    @Nationalized @Column(name="source_url", length=1000) private String sourceUrl;
    @Nationalized @Column(name="map_url", length=1000) private String mapUrl;
    @Column(name="is_active", nullable=false) private boolean active;
    @Column(name="verified_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime verifiedAt;
    @Column(name="created_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime createdAt;
    @Column(name="updated_at", nullable=false, columnDefinition="datetime2(3)") private LocalDateTime updatedAt;
}
