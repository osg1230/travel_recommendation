package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;

/** 장소 존재와 주소를 확인한 후보. 가격·영업시간·이동시간 검증과는 구분한다. */
public record PlaceCandidate(Long placeId, String name, String address, String subtype,
        String description, String sourceUrl, String mapSearchUrl, LocalDateTime verifiedAt,
        Double longitude,Double latitude,String sourceProvider, PlaceOperatingInfo operatingInfo) {
    public PlaceCandidate(Long id,String name,String address,String subtype,String description,String sourceUrl,String mapUrl,
            LocalDateTime verifiedAt,Double longitude,Double latitude,String sourceProvider) {
        this(id,name,address,subtype,description,sourceUrl,mapUrl,verifiedAt,longitude,latitude,sourceProvider,null);
    }
    public PlaceCandidate(Long id,String name,String address,String subtype,String description,String sourceUrl,String mapUrl,LocalDateTime verifiedAt) {
        this(id,name,address,subtype,description,sourceUrl,mapUrl,verifiedAt,null,null,"SYSTEM");
    }

    /** 간식·술집·카페처럼 끼니로 어울리지 않는 음식점 분류(예: 카카오 "음식점 > 간식 > 아이스크림", "음식점 > 술집 > 호프,요리주점"). */
    public static boolean notForMeals(String subtype) {
        return subtype != null && (subtype.contains("간식") || subtype.contains("술집") || subtype.contains("카페"));
    }

    /** 분류로 본 종교 시설(성당·사찰·성지). 미사·예불 중에는 관람이 제한될 수 있어 화면과 일정에 안내한다. */
    public boolean religiousSite() {
        return subtype != null && (subtype.contains("종교") || subtype.contains("사찰") || subtype.contains("성당"));
    }
}
