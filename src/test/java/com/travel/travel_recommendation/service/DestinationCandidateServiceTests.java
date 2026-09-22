package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DestinationCandidateServiceTests {
    private static long nextId = 1;
    private static Place place(String name, String region, String subtype) {
        var place = mock(Place.class);
        long id = nextId++;
        when(place.getId()).thenReturn(id);
        when(place.getName()).thenReturn(name);
        when(place.getRegion()).thenReturn(region);
        when(place.getSubtype()).thenReturn(subtype);
        return place;
    }
    private static List<String> names(List<Place> places) { return places.stream().map(Place::getName).toList(); }

    private final Place gangneung = place("강릉", "강원특별자치도 강릉시", "바다,자연,맛집,휴식");
    private final Place busan = place("부산", "부산광역시", "바다,맛집,문화·역사,체험·액티비티");
    private final Place gyeongju = place("경주", "경상북도 경주시", "문화·역사,자연");
    private final Place sokcho = place("속초", "강원특별자치도 속초시", "바다,자연,맛집");
    private final Place yeosu = place("여수", "전남광주통합특별시 여수시", "바다,맛집,휴식");
    private final Place jeonju = place("전주", "전북특별자치도 전주시", "문화·역사,맛집");
    private final Place jeju = place("제주", "제주특별자치도", "바다,자연,휴식,체험·액티비티");

    @Test void provinceCityAndFullRegionNamesMatchButSubstringsDoNot() {
        for (String query : List.of("강원", "강원도", "강원특별자치도", "강릉", "강릉시", "강원강릉", "강원도강릉시", "강원특별자치도강릉시"))
            assertThat(DestinationCandidateService.matches(gangneung, query)).as(query).isTrue();
        assertThat(DestinationCandidateService.matches(busan, "부산시")).isTrue();
        assertThat(DestinationCandidateService.matches(gyeongju, "경북")).isTrue();
        assertThat(DestinationCandidateService.matches(jeju, "제주도")).isTrue();
        for (String query : List.of("강릉부산", "부산아닌서울", "강", "경기", "릉"))
            assertThat(DestinationCandidateService.matches(gangneung, query)).as(query).isFalse();
    }

    // 2026-07-01 통합으로 광주·전남 선택값은 모두 전남광주통합특별시 여행지로 찾는다.
    @Test void gwangjuAndJeonnamOptionsFindTheMergedSpecialCity() {
        assertThat(DestinationCandidateService.matches(yeosu, "광주")).isTrue();
        assertThat(DestinationCandidateService.matches(yeosu, "전남")).isTrue();
        assertThat(DestinationCandidateService.matches(yeosu, "전라남도여수시")).isTrue();
        assertThat(DestinationCandidateService.matches(jeonju, "전남")).isFalse();
    }

    @Test void moreThanFiveCandidatesArePickedByThemeAndSpreadAcrossProvinces() {
        var all = List.of(gangneung, busan, gyeongju, sokcho, yeosu, jeonju, jeju);
        // 취향이 없으면 먼저 등록한 순서대로, 전국이면 한 시·도에 한 곳씩 먼저 고른다(속초는 강릉과 같은 강원이라 뒤로 밀린다).
        assertThat(names(DestinationCandidateService.pick(all, List.of(), true))).containsExactly("강릉", "부산", "경주", "여수", "전주");
        // '문화·역사'를 고르면 그 분류가 있는 곳이 앞에 온다.
        assertThat(names(DestinationCandidateService.pick(all, List.of("문화·역사"), true)).subList(0, 3)).containsExactlyInAnyOrder("부산", "경주", "전주");
        // 바다·휴식이면 둘 다 겹치는 곳(강릉·여수·제주)을 먼저 고른다.
        assertThat(names(DestinationCandidateService.pick(all, List.of("바다", "휴식"), true)).subList(0, 3)).containsExactly("강릉", "여수", "제주");
        // 시·도를 정한 조회는 5곳 이하면 그대로 둔다.
        assertThat(DestinationCandidateService.pick(List.of(gangneung, sokcho), List.of("바다"), false)).containsExactly(gangneung, sokcho);
        // 여행 목적 시설(예: 아쿠아리움)이 있는 여행지는 취향 점수보다 먼저 고른다.
        assertThat(names(DestinationCandidateService.pick(all, List.of("문화·역사"), true, List.of(jeju.getId(), yeosu.getId()))).subList(0, 2))
            .containsExactly("여수", "제주");
        // 같은 조건이면 늘 같은 결과다.
        assertThat(DestinationCandidateService.pick(all, List.of("맛집"), true)).isEqualTo(DestinationCandidateService.pick(all, List.of("맛집"), true));
    }
}
