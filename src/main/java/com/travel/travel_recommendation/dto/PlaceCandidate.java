package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;

/** 장소 존재와 주소를 확인한 후보. 가격·영업시간·이동시간 검증과는 구분한다. */
public record PlaceCandidate(Long placeId, String name, String address, String subtype,
        String description, String sourceUrl, String mapSearchUrl, LocalDateTime verifiedAt,
        Double longitude,Double latitude,String sourceProvider) {
    public PlaceCandidate(Long id,String name,String address,String subtype,String description,String sourceUrl,String mapUrl,LocalDateTime verifiedAt) {
        this(id,name,address,subtype,description,sourceUrl,mapUrl,verifiedAt,null,null,"SYSTEM");
    }
}
