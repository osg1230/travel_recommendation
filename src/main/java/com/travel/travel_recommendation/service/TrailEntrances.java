package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.Optional;
import java.util.Set;

/**
 * 카카오 좌표가 산 한가운데라 자동차 길찾기가 '도착 지점 주변 도로 없음'(result_code 103)으로 실패하는 산의 탐방로 입구.
 * 한라산국립공원은 좌표가 제주시 오등동 산 182라 일정에서 늘 빠졌다(2026-11-05 확인). 2026-11-09 사용자 결정으로
 * 한라산은 어리목탐방안내소를 길찾기 도착점으로 쓰고, 어리목~윗세오름 왕복을 기준으로 4시간 머문다고 본다.
 * 좌표와 주소는 2026-11-09 카카오 장소 검색 '어리목탐방안내소' 결과이고, 제주시청에서 자동차 길찾기 33분을 확인했다.
 * 탐방 시간은 흔히 안내하는 어리목~윗세오름 왕복 시간을 참고한 서버 기본값(예상)이며, 날씨·입산 통제는 확인하지 않는다.
 */
public final class TrailEntrances {
    private TrailEntrances() {}

    /** 길찾기에 쓸 입구 이름·주소·좌표와 그 산에 머무는 시간(분). */
    public record Entrance(String name, String address, double longitude, double latitude, int visitMinutes) {}

    static final Entrance EORIMOK = new Entrance("한라산 어리목탐방안내소", "제주특별자치도 제주시 1100로 2070-61",
        126.49506840496602, 33.39265304140202, 240);
    /** 카카오에서 같은 산을 가리키는 이름. 띄어쓰기는 빼고 비교한다. */
    private static final Set<String> HALLASAN_NAMES = Set.of("한라산국립공원", "한라산");

    /** 입구 좌표를 따로 쓰는 산이면 그 입구. 다른 지역의 같은 이름과 섞이지 않게 제주 주소인지도 본다. */
    public static Optional<Entrance> of(PlaceCandidate place) {
        String name = place.name() == null ? "" : place.name().replace(" ", "");
        String address = place.address() == null ? "" : place.address().strip();
        if (HALLASAN_NAMES.contains(name) && address.startsWith("제주")) return Optional.of(EORIMOK);
        return Optional.empty();
    }
}
