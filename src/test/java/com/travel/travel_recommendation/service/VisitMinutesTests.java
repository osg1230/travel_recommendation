package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VisitMinutesTests {
    private static PlaceCandidate place(String name, String subtype) {
        return new PlaceCandidate(1L, name, "강원 강릉시 경포로 1", subtype, "", "https://www.gn.go.kr/", "", LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // 기본 90분일 때 분류의 마지막 단계로 정한다. 가운뎃점·쉼표로 나뉜 분류는 앞의 이름을 먼저 본다.
    @Test void tableUsesLastCategoryStep() {
        assertThat(VisitMinutes.tableMinutes(place("경포대 전망", "여행 > 관광,명소 > 전망대"))).isEqualTo(40);
        assertThat(VisitMinutes.tableMinutes(place("경포해변", "여행 > 관광,명소 > 해수욕장,해변"))).isEqualTo(60);
        assertThat(VisitMinutes.tableMinutes(place("경포대", "누각·문화유산"))).isEqualTo(40);
        assertThat(VisitMinutes.tableMinutes(place("국립경주박물관", "박물관·문화유산"))).isEqualTo(90);
        assertThat(VisitMinutes.tableMinutes(place("불국사", "사찰·문화유산"))).isEqualTo(60);
        assertThat(VisitMinutes.tableMinutes(place("경포아쿠아리움", "문화,예술 > 문화시설 > 아쿠아리움"))).isEqualTo(120);
        assertThat(VisitMinutes.tableMinutes(place("설악워터피아", "여행 > 관광,명소 > 테마파크 > 워터테마파크"))).isEqualTo(180);
        assertThat(VisitMinutes.tableMinutes(place("낭도", "여행 > 관광,명소 > 섬 > 섬(내륙)"))).isEqualTo(120);
        // '문화유산'에 '산'이 들어 있어도 산(120분)으로 보지 않는다. 이름 전체가 같아야 한다.
        assertThat(VisitMinutes.tableMinutes(place("오죽헌", "문화유산"))).isEqualTo(60);
        // 넓은 분류는 기본 그대로이고, 이름 끝이 스카이워크·전망대 같은 곳만 짧게 본다.
        assertThat(VisitMinutes.tableMinutes(place("감천문화마을", "여행 > 관광,명소"))).isEqualTo(90);
        assertThat(VisitMinutes.tableMinutes(place("오륙도 스카이워크", "여행 > 관광,명소"))).isEqualTo(40);
        assertThat(VisitMinutes.tableMinutes(place("장소1", null))).isEqualTo(90);
    }

    // 기본 체류시간을 바꾸면 같은 비율로 늘거나 줄고, 10분 단위로 반올림해 30~240분 안에 둔다. 기본과 같은 종류는 입력한 시간 그대로다.
    @Test void scalesWithBaseMinutes() {
        var view = place("전망대", "여행 > 관광,명소 > 전망대");
        var beach = place("해변", "여행 > 관광,명소 > 해수욕장,해변");
        var aquarium = place("아쿠아리움", "문화,예술 > 문화시설 > 아쿠아리움");
        var waterPark = place("워터파크", "여행 > 관광,명소 > 워터테마파크");
        var plain = place("장소", "여행 > 관광,명소");
        assertThat(VisitMinutes.of(plain, 90)).isEqualTo(90);
        assertThat(VisitMinutes.of(plain, 75)).isEqualTo(75);
        assertThat(VisitMinutes.of(beach, 75)).isEqualTo(50);
        assertThat(VisitMinutes.of(view, 60)).isEqualTo(30);
        assertThat(VisitMinutes.of(beach, 60)).isEqualTo(40);
        assertThat(VisitMinutes.of(aquarium, 60)).isEqualTo(80);
        assertThat(VisitMinutes.of(view, 120)).isEqualTo(50);
        assertThat(VisitMinutes.of(waterPark, 120)).isEqualTo(240);
        assertThat(VisitMinutes.of(waterPark, 180)).isEqualTo(240);
        assertThat(VisitMinutes.of(aquarium, 30)).isEqualTo(40);
    }
}
