package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 지역 대표 음식 안내(SFR-17-01). 2026-09-21 각 공식 출처에서 음식 이름을 확인했다.
 * 특정 음식점의 메뉴가 아니라 지역 안내이므로, 일정의 음식점에서 파는지는 따로 확인해야 한다.
 */
public final class RegionalFoods {
    private RegionalFoods() {}
    public record Source(String name, String url) {}
    /** foods는 대표 음식 이름, snack이면 식사가 아닌 간식류다. */
    public record Guide(String region, List<String> foods, boolean snack, List<Source> sources) {
        /** "초당두부·옹심이·물회" 같은 짧은 표기. */
        public String joined() { return String.join("·", foods); }
    }
    private static final Map<String,Guide> GUIDES=Map.of(
        "강릉",new Guide("강릉",List.of("초당두부","옹심이","물회","장칼국수"),false,List.of(
            new Source("강릉시 유네스코 창의도시 전통음식","https://www.gn.go.kr/creativecity/html/food/food2"))),
        "부산",new Guide("부산",List.of("돼지국밥","밀면"),false,List.of(
            new Source("비짓부산 돼지국밥","https://www.visitbusan.net/index.do?menuCd=DOM_000000201001001000&uc_seq=494&lang_cd=ko"),
            new Source("비짓부산 밀면","https://www.visitbusan.net/index.do?menuCd=DOM_000000202003001000&uc_seq=312&lang_cd=ko"))),
        "경주",new Guide("경주",List.of("황남빵","찰보리빵"),true,List.of(
            new Source("한국관광공사 대한민국 구석구석","https://korean.visitkorea.or.kr/detail/rem_detail.do?cotid=eba1b347-4860-404f-a878-91f28b5ddc33"))));

    /** 여행지 이름의 대표 음식 안내. 확인한 자료가 없는 지역은 비어 있다. */
    public static Optional<Guide> of(String destination) { return Optional.ofNullable(GUIDES.get(destination)); }
}
