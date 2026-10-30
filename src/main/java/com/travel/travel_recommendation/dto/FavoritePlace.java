package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;

/**
 * 즐겨찾기 목록에 표시할 장소 정보. 등록 당시가 아닌 현재 장소 정보를 보여 준다.
 * active=false면 더 이상 확인되지 않는 장소라 목록에 표시만 하고 삭제할 수 있게 한다.
 */
public record FavoritePlace(Long favoriteId, Long placeId, String placeType, String typeName,
        String name, String region, String address, String sourceUrl, String mapUrl, LocalDateTime createdAt, boolean active) {}
