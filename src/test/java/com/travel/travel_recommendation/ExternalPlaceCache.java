package com.travel.travel_recommendation;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 화면에서 '카카오에서 장소 더 찾아보기'를 하면 KAKAO 장소가 24시간 유효한 캐시로 DB에 남는다.
 * 지역별 장소 수·첫 후보를 확인하는 테스트는 이 행 때문에 결과가 바뀌므로, 테스트 트랜잭션 안에서 기존 캐시를 만료 처리한다.
 * 테스트가 끝나면 롤백돼 실제 캐시는 바뀌지 않는다. 호출한 뒤 테스트가 새로 만드는 KAKAO 행은 그대로 유효하다.
 */
final class ExternalPlaceCache {
    private ExternalPlaceCache() {}

    static void expireExisting(JdbcTemplate jdbc) {
        jdbc.update("UPDATE dbo.places SET verified_at=DATEADD(DAY,-2,SYSUTCDATETIME()) WHERE source_provider='KAKAO'");
    }
}
