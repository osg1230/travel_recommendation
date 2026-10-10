package com.travel.travel_recommendation.dto;

import java.time.Instant;

/**
 * 한국관광공사 TourAPI(detailPetTour2)에서 받은 장소의 반려동물 동반 정보. 원문을 그대로 보관한다.
 * allowedArea(동반 구분)가 비어 있으면 관광공사 콘텐츠는 찾았지만 반려동물 정보가 등록되지 않은 장소다.
 * fetchedAt은 조회 시각이며 정보 자체의 갱신일이 아니다.
 */
public record PlacePetInfo(String contentId, String allowedArea, String animals, String requirements, String etc, Instant fetchedAt) {
    /** 관광공사 콘텐츠는 있지만 반려동물 정보가 없는 장소. 같은 장소를 며칠 동안 다시 조회하지 않도록 보관한다. */
    public static PlacePetInfo none(String contentId) { return new PlacePetInfo(contentId, "", "", "", "", Instant.now()); }

    public boolean found() { return allowedArea != null && !allowedArea.isBlank(); }

    /** 동반 구분 원문(예: "전구역 동반가능", "일부구역 동반가능")을 판정 값으로 나눈다. */
    public String status() {
        if (!found()) return "UNKNOWN";
        if (allowedArea.contains("불가")) return "NOT_ALLOWED";
        if (allowedArea.contains("일부")) return "PARTIAL";
        if (allowedArea.contains("가능")) return "ALLOWED";
        return "UNKNOWN";
    }

    /** 화면용 판정 이름. */
    public String label() {
        return switch (status()) {
            case "ALLOWED" -> "동반 가능";
            case "PARTIAL" -> "일부 구역 동반 가능";
            case "NOT_ALLOWED" -> "동반 불가";
            default -> "정보 없음";
        };
    }
}
