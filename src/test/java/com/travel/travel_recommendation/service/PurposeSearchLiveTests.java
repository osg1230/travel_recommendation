package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.KakaoMapProperties;
import com.travel.travel_recommendation.config.OpenAiProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

/** 실제 OpenAI(검색어)·카카오(전국 검색) 호출. RUN_PURPOSE_LIVE_TESTS=true일 때만 실행하고 키는 출력하지 않는다. */
@EnabledIfEnvironmentVariable(named = "RUN_PURPOSE_LIVE_TESTS", matches = "true")
class PurposeSearchLiveTests {
    // 005·012·014 SQL로 넣은 여행지 지역명.
    private static final List<String> REGIONS = List.of("강원특별자치도 강릉시", "부산광역시", "경상북도 경주시", "강원특별자치도 속초시",
        "강원특별자치도 양양군", "울산광역시", "서울특별시", "대구광역시", "인천광역시", "대전광역시", "세종특별자치시", "경기도 수원시",
        "경기도 가평군", "강원특별자치도 춘천시", "강원특별자치도 평창군", "충청북도 단양군", "충청남도 공주시", "충청남도 태안군",
        "전북특별자치도 전주시", "전북특별자치도 군산시", "전남광주통합특별시 여수시", "전남광주통합특별시 순천시", "전남광주통합특별시 담양군",
        "경상북도 안동시", "경상북도 포항시", "경상남도 통영시", "경상남도 거제시", "제주특별자치도");

    @Test void sharkPurposeFindsAquariumsInServiceRegions() {
        String key = System.getenv("OPENAI_API_KEY") != null ? System.getenv("OPENAI_API_KEY") : System.getenv("Portfolio_java_API");
        var ai = new OpenAiPurposeClient(new OpenAiProperties(key, "gpt-4.1-mini"), new JsonMapper());
        var maps = new KakaoMapClient(new KakaoMapProperties(System.getenv("KAKAO_REST_API_KEY")), new JsonMapper());
        for (String purpose : List.of("상어 관람", "별 보기", "휴식")) {
            var terms = ai.terms(purpose); var keywords = terms.keywords();
            System.out.println("목적: " + purpose + " → 검색어 " + keywords + " · 가까운 분류 " + terms.themes());
            for (String keyword : keywords)
                for (var place : maps.searchNationwide(keyword, REGIONS))
                    System.out.println("  " + keyword + " · " + place.region() + " · " + place.name() + " · " + place.category());
        }
        assertThat(ai.terms("상어 관람").keywords()).isNotEmpty();
    }
}
