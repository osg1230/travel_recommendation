package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_KAKAO_TESTS", matches="true")
class KakaoMapLiveTests {
    // 005·012·014 SQL로 넣은 여행지 28곳의 지역명. 주소 앞부분이 이 지역과 맞는 후보가 실제로 나오는지 본다.
    private static final java.util.List<String> REGIONS=java.util.List.of("강원특별자치도 강릉시","부산광역시","경상북도 경주시",
        "강원특별자치도 속초시","강원특별자치도 양양군","울산광역시","서울특별시","대구광역시","인천광역시","대전광역시","세종특별자치시",
        "경기도 수원시","경기도 가평군","강원특별자치도 춘천시","강원특별자치도 평창군","충청북도 단양군","충청남도 공주시","충청남도 태안군",
        "전북특별자치도 전주시","전북특별자치도 군산시","전남광주통합특별시 여수시","전남광주통합특별시 순천시","전남광주통합특별시 담양군",
        "경상북도 안동시","경상북도 포항시","경상남도 통영시","경상남도 거제시","제주특별자치도");
    @Test void discoversSupportedRegions() {
        var client=new KakaoMapClient(new KakaoMapProperties(System.getenv("KAKAO_REST_API_KEY")),new JsonMapper());
        for(String region:REGIONS) {
            for(String type:java.util.List.of("ATTRACTION","RESTAURANT","ACCOMMODATION")) {
                var places=client.searchPlaces(region,type);
                assertThat(places).isNotEmpty().allSatisfy(p->{
                    assertThat(p.region()).isEqualTo(region); assertThat(p.type()).isEqualTo(type);
                    assertThat(p.url()).startsWith("https://place.map.kakao.com/");
                });
                System.out.println("카카오 검색: "+region+" / "+type+" / 검증 후보 "+places.size()+"곳");
            }
        }
    }
    @Test void verifiesKnownAddressesAndBothTransportModes() {
        // 실제 호출은 명시적으로 선택한다. 키와 전체 응답은 출력하지 않는다.
        var properties=new KakaoMapProperties(System.getenv("KAKAO_REST_API_KEY"));
        assertThat(properties.isConfigured()).isTrue();
        var client=new KakaoMapClient(properties,new JsonMapper());
        var from=client.locate("강원특별자치도 강릉시 율곡로3139번길 24");
        var to=client.locate("강원특별자치도 강릉시 경포로 365");
        for (var mode:KakaoMapClient.Transport.values()) {
            var result=client.travelTime(from,to,mode);
            assertThat(result.transport()).isEqualTo(mode);
            assertThat(result.seconds()).isPositive();
            assertThat(result.meters()).isPositive();
        }
    }
}
