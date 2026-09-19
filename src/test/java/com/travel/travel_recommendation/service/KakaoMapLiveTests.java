package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_KAKAO_TESTS", matches="true")
class KakaoMapLiveTests {
    @Test void discoversSupportedRegions() {
        var client=new KakaoMapClient(new KakaoMapProperties(System.getenv("KAKAO_REST_API_KEY")),new JsonMapper());
        for(String region:java.util.List.of("강원특별자치도 강릉시","부산광역시","경상북도 경주시")) {
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
