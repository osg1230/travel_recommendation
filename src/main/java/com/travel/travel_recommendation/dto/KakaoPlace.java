package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;

/** 카카오 검색에서 지역·유형·식별자·좌표를 검사한 장소. 영업 여부는 포함하지 않는다. */
public record KakaoPlace(String sourceId,String type,String name,String region,String address,
                         String category,double longitude,double latitude,String url,LocalDateTime checkedAt) {}
