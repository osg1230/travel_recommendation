package com.travel.travel_recommendation.service;

import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class DayRouteTests {
    // 한 줄 위의 위치(km)로 본 장소. 두 장소 사이 거리는 위치 차이다.
    private static double leg(Double a, Double b) { return Math.abs(a - b); }

    // 0 → 10 → 2 → 8처럼 왔다 갔다 하는 순서를 0 → 2 → 8 → 10으로 바꾼다.
    @Test void findsShorterOrderForZigzag() {
        var order = DayRoute.shortest(List.of(0.0, 10.0, 2.0, 8.0), null, null, DayRouteTests::leg);
        assertThat(order).containsExactly(0.0, 2.0, 8.0, 10.0);
        assertThat(DayRoute.length(List.of(0.0, 10.0, 2.0, 8.0), null, null, DayRouteTests::leg)).isEqualTo(24.0);
        assertThat(DayRoute.length(order, null, null, DayRouteTests::leg)).isEqualTo(10.0);
    }

    // 숙소(5)에서 나와 숙소로 돌아오는 날은 숙소까지의 거리도 센다. 숙소 쪽에서 시작해 한쪽 끝을 돌고 돌아오는 순서가 짧다.
    @Test void countsStartAndEnd() {
        var stops = List.of(9.0, 1.0, 6.0);
        var order = DayRoute.shortest(stops, 5.0, 5.0, DayRouteTests::leg);
        assertThat(DayRoute.length(stops, 5.0, 5.0, DayRouteTests::leg)).isEqualTo(4 + 8 + 5 + 1);
        assertThat(DayRoute.length(order, 5.0, 5.0, DayRouteTests::leg)).isEqualTo(16.0);
    }

    // 이미 가장 짧으면 순서를 그대로 둔다. 장소가 너무 많으면 비교하지 않고 그대로 돌려준다.
    @Test void keepsOrderWhenAlreadyShortestOrTooMany() {
        assertThat(DayRoute.shortest(List.of(1.0, 2.0, 3.0), null, null, DayRouteTests::leg)).containsExactly(1.0, 2.0, 3.0);
        var many = List.of(7.0, 1.0, 6.0, 2.0, 5.0, 3.0, 4.0);
        assertThat(many).hasSizeGreaterThan(DayRoute.MAX_STOPS);
        assertThat(DayRoute.shortest(many, null, null, DayRouteTests::leg)).containsExactlyElementsOf(many);
        assertThat(DayRoute.shortest(List.of(3.0), 0.0, 0.0, DayRouteTests::leg)).containsExactly(3.0);
    }
}
