package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;

/** 즐겨찾기 목록에 표시할 장소 정보. 등록 당시가 아닌 현재 장소 정보를 보여 준다. */
public record FavoritePlace(Long favoriteId, Long placeId, String placeType, String typeName,
        String name, String region, String address, String sourceUrl, String mapUrl, LocalDateTime createdAt) {}
