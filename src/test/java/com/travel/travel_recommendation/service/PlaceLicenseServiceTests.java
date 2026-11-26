package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.KakaoPlace;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.service.LicenseClient.Business;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PlaceLicenseServiceTests {
    private final JsonMapper mapper = new JsonMapper();
    private static final String ADDRESS = "강원 강릉시 난설헌로 201";
    private Business business(String name, String address, String status) { return new Business(name, address, status, "03".equals(status) ? "2026-11-03" : ""); }
    private Place place(String name) {
        return Place.fromKakao(new KakaoPlace("123", "RESTAURANT", name, "강원특별자치도 강릉시", ADDRESS, "음식점 > 양식",
            128.9, 37.8, "https://place.map.kakao.com/123", LocalDateTime.of(2026, 11, 26, 0, 0)));
    }

    // 도로명·건물번호가 같고 이름 한쪽이 다른 쪽에 들어 있어야 같은 사업장이다. 시·도 표기와 인허가 주소의 층·호·동 이름은 달라도 된다.
    @Test void sameBusinessNeedsSameRoadNumberAndOverlappingName() {
        assertThat(PlaceLicenseService.sameBusiness("파스타예요 강릉초당점", ADDRESS, business("파스타예요 강릉초당점", "강원특별자치도 강릉시 난설헌로 201 (초당동)", "03"))).isTrue();
        assertThat(PlaceLicenseService.sameBusiness("테라로사 서울역점", "서울 용산구 청파로 378", business("(주)학산 테라로사 서울역점", "서울특별시 용산구 청파로 378, 3층 214호 (동자동)", "01"))).isTrue();
        assertThat(PlaceLicenseService.sameBusiness("동화가든 본점", ADDRESS, business("동화가든", "강원특별자치도 강릉시 난설헌로 201", "01"))).isTrue();
        assertThat(PlaceLicenseService.sameBusiness("파스타예요 강릉초당점", ADDRESS, business("파스타예요 강릉초당점", "강원특별자치도 강릉시 난설헌로 201-1 (초당동)", "03"))).isFalse();
        assertThat(PlaceLicenseService.sameBusiness("파스타예요 강릉초당점", ADDRESS, business("초당순두부", "강원특별자치도 강릉시 난설헌로 201 (초당동)", "03"))).isFalse();
        assertThat(PlaceLicenseService.sameBusiness("파스타예요 강릉초당점", ADDRESS, business("파스타예요 강릉초당점", "", "03"))).isFalse();
    }

    // 폐업 기록만 있을 때만 폐업이다. 같은 자리에 같은 이름으로 다시 허가받아 영업 중이거나 기록을 못 찾으면 폐업으로 보지 않는다.
    @Test void closedOnlyWhenNoOpenRecordRemains() {
        var closed = business("가게", "", "03"); var open = business("가게", "", "01"); var cancelled = business("가게", "", "04");
        assertThat(PlaceLicenseService.closedDown(List.of(closed))).isTrue();
        assertThat(PlaceLicenseService.closedDown(List.of(closed, cancelled))).isTrue();
        assertThat(PlaceLicenseService.closedDown(List.of(closed, open))).isFalse();
        assertThat(PlaceLicenseService.closedDown(List.of(cancelled))).isFalse();
        assertThat(PlaceLicenseService.closedDown(List.of())).isFalse();
    }

    @Test void searchesByFirstWordOfTheName() {
        assertThat(PlaceLicenseService.keyword("동화가든 본점")).isEqualTo("동화가든");
        assertThat(PlaceLicenseService.keyword("초당할머니순두부")).isEqualTo("초당할머니순두부");
        assertThat(PlaceLicenseService.keyword("더 키친")).isEqualTo("더키친");
        assertThat(PlaceLicenseService.keyword("짬")).isEmpty();
        assertThat(PlaceLicenseService.keyword(" ")).isEmpty();
    }

    // 확인 결과는 다른 상세정보를 보존하며 저장하고, 폐업은 30일, 다시 조회하지 않는 기간은 7일이다. 이름이 바뀐 장소에는 쓰지 않는다.
    @Test void storesStatusBesideOtherDetailsAndExpires() {
        var now = Instant.parse("2026-11-26T03:00:00Z");
        var place = place("파스타예요 강릉초당점");
        place.updateDetails("{\"searchRank\":3}");
        assertThat(PlaceLicenseDetails.closed(place, mapper, now)).isFalse();
        assertThat(PlaceLicenseDetails.checkedRecently(place, mapper, now)).isFalse();

        place.updateDetails(PlaceLicenseDetails.write(place, true, "2026-11-03", now, mapper));
        assertThat(place.getDetailsJson()).contains("searchRank", "licenseStatus", "2026-11-03");
        assertThat(PlaceLicenseDetails.closed(place, mapper, now.plus(Duration.ofDays(29)))).isTrue();
        assertThat(PlaceLicenseDetails.closed(place, mapper, now.plus(Duration.ofDays(31)))).isFalse();
        assertThat(PlaceLicenseDetails.checkedRecently(place, mapper, now.plus(Duration.ofDays(6)))).isTrue();
        assertThat(PlaceLicenseDetails.checkedRecently(place, mapper, now.plus(Duration.ofDays(8)))).isFalse();

        var open = place("초당할머니순두부");
        open.updateDetails(PlaceLicenseDetails.write(open, false, "", now, mapper));
        assertThat(PlaceLicenseDetails.closed(open, mapper, now)).isFalse();
        assertThat(PlaceLicenseDetails.checkedRecently(open, mapper, now)).isTrue();

        var renamed = place("새 가게");
        renamed.updateDetails(place.getDetailsJson());
        assertThat(PlaceLicenseDetails.closed(renamed, mapper, now)).isFalse();
    }
}
