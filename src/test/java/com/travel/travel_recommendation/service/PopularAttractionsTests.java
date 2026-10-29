package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.service.TourApiClient.RelatedPlace;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class PopularAttractionsTests {
    private static RelatedPlace row(String center, String name, String middle, String small, int rank) {
        return new RelatedPlace(center, name, "51150", "관광지", middle, small, rank);
    }

    // 여러 중심 관광지에서 함께 간 곳으로 많이 나올수록 앞에 둔다. 같은 횟수면 평균 순위가 높은(숫자가 작은) 곳이 앞이다.
    @Test void moreCentersFirstThenBetterAverageRank() {
        var rows = List.of(
            row("경포대", "강릉중앙시장", "쇼핑", "시장", 5), row("오죽헌", "강릉중앙시장", "쇼핑", "시장", 9),
            row("경포대", "안목해변", "자연관광", "자연경관(하천/해양)", 3), row("오죽헌", "안목해변", "자연관광", "자연경관(하천/해양)", 2),
            row("경포대", "오죽헌", "역사관광", "역사유적지", 1));
        assertThat(PopularAttractions.rank(rows, Set.of("51150"), 20)).containsExactly("안목해변", "강릉중앙시장", "오죽헌");
    }

    // 숙박·음식, 역·터미널(교통시설), 백화점 같은 시장 아닌 쇼핑, 여행지 밖(다른 시·군)의 곳은 뺀다.
    @Test void dropsLodgingFoodTransportShoppingAndOutsidePlaces() {
        var rows = List.of(
            new RelatedPlace("경포대", "스카이베이호텔", "51150", "숙박", "숙박", "호텔", 1),
            new RelatedPlace("경포대", "초당두부", "51150", "음식", "음식", "한식", 1),
            row("경포대", "강릉역", "기타관광", "교통시설", 1),
            row("경포대", "신세계백화점", "쇼핑", "백화점", 1),
            new RelatedPlace("경포대", "속초관광수산시장", "51210", "관광지", "쇼핑", "시장", 1),
            row("경포대", "경포해변", "자연관광", "자연경관(하천/해양)", 4));
        assertThat(PopularAttractions.rank(rows, Set.of("51150"), 20)).containsExactly("경포해변");
    }

    @Test void namesAreCleanedForKakaoSearchAndLimited() {
        assertThat(PopularAttractions.clean("아르떼뮤지엄/강릉")).isEqualTo("아르떼뮤지엄 강릉");
        assertThat(PopularAttractions.clean("경주시 강동면 경주양동마을")).isEqualTo("경주양동마을");
        assertThat(PopularAttractions.clean("해운대블루라인파크/미포정거장")).isEqualTo("해운대블루라인파크 미포정거장");
        var rows = new ArrayList<RelatedPlace>();
        for (int i = 1; i <= 25; i++) rows.add(row("중심", "관광지" + i, "문화관광", "전시시설", i));
        assertThat(PopularAttractions.rank(rows, Set.of("51150"), 20)).hasSize(20).startsWith("관광지1");
    }
}
