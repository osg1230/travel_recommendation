package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.MemberStats;
import com.travel.travel_recommendation.entity.RecommendationRequest;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class SimilarMemberServiceTests {
    private final SimilarMemberService service=new SimilarMemberService(null,null,null,null,null,null,new JsonMapper());

    private static RecommendationRequest request(String region,Integer days,String keywordsJson) {
        var request=mock(RecommendationRequest.class);
        when(request.getRegion()).thenReturn(region);
        when(request.getDurationDays()).thenReturn(days==null?null:days.shortValue());
        when(request.getConditionsJson()).thenReturn("{\"schema_version\":1,\"selected_keywords\":"+keywordsJson+"}");
        return request;
    }

    // 여행 취향·동행자·계절 키워드가 겹치는 수에 같은 지역, 같은 기간을 하나씩 더한다. 이동수단은 세지 않는다.
    @Test void scoreCountsSharedKeywordsRegionAndDuration() {
        var keywords=Set.of("#3","#7","#12");
        var other=request("강원특별자치도",2,"""
            [{"keyword_id":3,"category_code":"THEME","name":"바다"},{"keyword_id":7,"category_code":"COMPANION","name":"친구"},
             {"keyword_id":20,"category_code":"TRANSPORTATION","name":"자동차"}]""");
        // 키워드 2개 + 강원(같은 시·도) + 2일
        assertThat(service.score(keywords,"강원",2,other)).isEqualTo(4);
        // 기간이 다르고 지역을 정하지 않았으면 키워드만 센다.
        assertThat(service.score(keywords,"",3,other)).isEqualTo(2);
        // 이동수단 키워드는 같아도 세지 않는다.
        assertThat(service.score(Set.of("#20"),"부산",3,other)).isZero();
    }

    // 키워드 ID가 없는 예전 기록은 분류와 이름으로 비교한다.
    @Test void keywordsWithoutIdsAreComparedByCategoryAndName() {
        var other=request("부산광역시",null,"[{\"category_code\":\"SEASON\",\"name\":\"가을\"}]");
        assertThat(service.score(Set.of("SEASON:가을"),"부산",2,other)).isEqualTo(2);
        assertThat(service.score(Set.of("THEME:가을"),"부산",2,other)).isEqualTo(1);
    }

    @Test void statsTextShowsOnlyNonZeroCounts() {
        assertThat(new MemberStats(0,0,0).empty()).isTrue();
        assertThat(new MemberStats(0,2,1).text()).isEqualTo("비슷한 조건으로 여행한 회원 기록 · 즐겨찾기 2명 · 4점 이상 평가 1명");
    }
}
