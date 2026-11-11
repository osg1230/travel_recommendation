package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.Constraints;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class RelaxedPaceTests {
    private static ConfirmedTravelConditions conditions(String original, String purpose, String... themes) {
        var keywords = java.util.Arrays.stream(themes).map(name -> new TravelKeyword(1L, "THEME", name, null)).toList();
        return new ConfirmedTravelConditions("TEXT", original, "강원", null, null, 2, null, null, true, null, keywords, "", true, purpose, null, false, List.of());
    }
    private static PlaceCandidate place(String name, String subtype) {
        return new PlaceCandidate(1L, name, "강원 강릉시 경포로 1", subtype, "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // 여행 목적이나 처음 입력한 문장에 여유를 뜻하는 말이 있으면 여유 일정으로 본다. "빡빡하게"는 아니다.
    @Test void detectsRelaxedPaceFromPurposeOrOriginalText() {
        assertThat(RelaxedPace.wanted(conditions("아이랑 강원도 바다로 가요. 너무 빡빡하지 않게 짜 주세요.", null))).isTrue();
        assertThat(RelaxedPace.wanted(conditions("", "여유롭게 바다 보기"))).isTrue();
        assertThat(RelaxedPace.wanted(conditions("부모님과 천천히 둘러보고 싶어요", ""))).isTrue();
        assertThat(RelaxedPace.wanted(conditions("많이 보고 싶어요. 빡빡하게 짜 주세요.", "맛집"))).isFalse();
        assertThat(RelaxedPace.wanted(conditions("친구와 강릉 2박 3일", null))).isFalse();
        assertThat(RelaxedPace.wanted(null)).isFalse();
    }

    // 여유 일정이면 하루 3곳을 선호 조건으로 넣는다. 사용자가 정한 하루 방문 수와 다른 조건은 그대로 둔다.
    @Test void relaxedPaceSetsPreferredDailyVisitsOnlyWhenNotChosen() {
        var relaxed = conditions("느긋하게 다니고 싶어요", null);
        var made = RelaxedPace.apply(null, relaxed);
        assertThat(made.dailyVisits()).isEqualTo(3);
        assertThat(made.dailyVisitsRequired()).isFalse();

        var chosen = new Constraints(List.of(7L), 5, true, null, List.of(), "STANDARD", List.of(7L));
        assertThat(RelaxedPace.apply(chosen, relaxed)).isSameAs(chosen);

        var open = new Constraints(List.of(7L), null, false, null, List.of(), "STANDARD", List.of(7L));
        var filled = RelaxedPace.apply(open, relaxed);
        assertThat(filled.dailyVisits()).isEqualTo(3);
        assertThat(filled.requiredPlaceIds()).containsExactly(7L);
        assertThat(filled.stayTier()).isEqualTo("STANDARD");
        assertThat(filled.centerPlaceIds()).containsExactly(7L);

        assertThat(RelaxedPace.apply(null, conditions("친구와 강릉", null))).isNull();
        assertThat(RelaxedPace.apply(open, conditions("친구와 강릉", null))).isSameAs(open);
    }

    // 여유 일정에서 바다 취향을 골랐을 때만 해변을 120분으로 잡는다. 다른 종류와 다른 여행은 종류별 체류시간 그대로다.
    @Test void beachStayIsLongerOnlyForRelaxedSeaTrips() {
        var beach = place("경포해수욕장", "여행 > 관광,명소 > 해수욕장,해변");
        var museum = place("오죽헌시립박물관", "박물관");
        var relaxedSea = conditions("여유롭게 바다에서 놀고 싶어요", null, "바다");
        assertThat(RelaxedPace.visitMinutes(relaxedSea, beach, 90)).isEqualTo(120);
        assertThat(RelaxedPace.visitMinutes(relaxedSea, museum, 90)).isEqualTo(90);
        assertThat(RelaxedPace.visitMinutes(conditions("여유롭게 쉬고 싶어요", null, "휴식"), beach, 90)).isEqualTo(60);
        assertThat(RelaxedPace.visitMinutes(conditions("바다 보러 가요", null, "바다"), beach, 90)).isEqualTo(60);
        assertThat(RelaxedPace.visitMinutes(null, beach, 90)).isEqualTo(60);
    }
}
