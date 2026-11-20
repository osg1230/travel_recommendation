package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.*;

/**
 * 관광지 종류별 체류시간(2026-10-31). 사용자가 정한 체류시간을 '기본 체류시간'으로 보고 장소 종류에 따라 짧게·길게 잡는다.
 * 예전에는 전망대도 아쿠아리움도 같은 시간(예: 90분)이었다.
 * 아래 표의 분은 기본 체류시간이 90분일 때의 값이고, 기본값을 바꾸면 같은 비율로 늘거나 준다(10분 단위, 30~240분).
 * 종류별 시간은 여행 안내에서 흔히 잡는 관람 시간을 참고한 서버 기본값(예상)이며, 장소마다 확인한 시간이 아니다.
 * 2026-11-20에 "머무는 시간이 짧다"는 피드백으로 올렸다. 참고: 해수욕장 체류 2.3~5.0시간(KMI 통신 데이터, 주변 식당 포함),
 * 박물관 평균 111분(미국 유타 자연사박물관 조사), 아쿠아리움 2~3시간·동물원 3~5시간(여행 안내의 권장 시간).
 */
public final class VisitMinutes {
    private VisitMinutes() {}
    /** 표의 분은 기본 체류시간이 이 값(빠른 일정 기본값 90분)일 때다. */
    static final int TABLE_BASE = 90;
    /** 계산한 체류시간의 범위(분). 오래 머무는 곳도 하루 일정을 다 차지하지 않게 240분까지만 둔다. */
    static final int MIN = 30, MAX = 240;

    /**
     * 분류 이름 → 기본 90분일 때의 체류시간. 카카오·관광공사 분류의 마지막 단계에 나오는 이름이다(예: "해수욕장,해변" → 해수욕장).
     * 표에 없는 분류(예: 관광,명소)는 기본 체류시간 그대로다.
     */
    private static final Map<String, Integer> KIND_MINUTES = new HashMap<>();
    static {
        // 잠깐 들러 보고 사진을 찍는 곳
        put(40, "전망대", "문", "탑", "비석", "누각", "정자", "바위", "불상", "석불", "사당", "제단", "등대", "관광안내소");
        // 한 바퀴 둘러보면 끝나는 작은 곳
        put(60, "성당", "교회", "서원", "향교", "서당", "생가", "고택", "릉", "묘", "총", "테마거리", "카페거리", "먹자골목", "거리",
            "광장", "저수지", "연못", "하천", "강", "폭포", "동굴", "케이블카", "레일바이크", "산봉우리");
        // 걷고 쉬며 둘러보는 곳(기본과 같다). 해변·사찰·시장·공원은 2026-11-20에 60분에서 올렸다.
        put(90, "해수욕장", "해변", "해안", "바닷가", "절", "사찰", "종교유적지", "문화유적", "유적지", "문화유산", "공원", "도시근린공원",
            "호수", "계곡", "전통시장", "시장", "도보여행", "무장애나눔길", "오름", "체험학습장", "체험여행", "체험마을", "관광농원",
            "산성", "성곽", "마을", "관광단지", "유원지");
        // 전시를 보거나 넓은 곳을 걷는 곳
        put(120, "박물관", "미술관", "전시관", "기념관", "과학관", "수목원", "식물원", "정원", "국가정원", "자연휴양림", "고궁", "궁",
            "온천", "스파", "섬");
        // 오래 머물거나 걸어서 오르는 곳
        put(150, "아쿠아리움", "동물원", "산", "등산로", "국립공원", "도립공원", "제주올레길", "해파랑길",
            "둘레길", "서울둘레길", "북한산둘레길", "자전거여행");
        // 반나절을 보내는 곳
        put(240, "테마파크", "놀이공원", "워터테마파크", "워터파크");
    }
    private static void put(int minutes, String... kinds) { for (String kind : kinds) KIND_MINUTES.put(kind, minutes); }

    /** 분류가 '관광,명소'처럼 넓을 때 이름 끝으로 잠깐 들르는 곳을 알아본다(예: ○○스카이워크). */
    private static final List<String> SHORT_NAME_ENDINGS = List.of("전망대", "스카이워크", "출렁다리", "등대", "방파제");

    /**
     * 기본 체류시간(base분)을 정한 사용자가 이 장소에 머무는 시간(분). 표의 분을 base/90배 하고 10분 단위로 반올림한다.
     * 기본과 같은 종류(박물관·분류가 넓은 곳 등)는 사용자가 정한 시간을 그대로 쓴다.
     */
    public static int of(PlaceCandidate place, int base) {
        // 탐방로 입구로 가는 산(한라산)은 코스를 걷는 시간이 정해져 있어 기본 체류시간과 상관없이 그 시간을 쓴다.
        var entrance = TrailEntrances.of(place);
        if (entrance.isPresent()) return entrance.get().visitMinutes();
        int table = tableMinutes(place);
        if (table == TABLE_BASE) return base;
        int minutes = (int) Math.round(table * base / (double) TABLE_BASE / 10.0) * 10;
        return Math.max(MIN, Math.min(MAX, minutes));
    }

    /**
     * 기본 90분일 때 이 장소의 체류시간. 분류의 마지막 단계를 쉼표·가운뎃점·공백으로 나눠 앞에서부터 표에 있는 이름을 찾는다
     * (예: "누각·문화유산"은 누각 40분). 표에 없으면 이름 끝을 보고, 그래도 없으면 90분이다.
     */
    static int tableMinutes(PlaceCandidate place) {
        for (String kind : kinds(place.subtype())) {
            Integer minutes = KIND_MINUTES.get(kind);
            if (minutes != null) return minutes;
        }
        String name = place.name() == null ? "" : place.name().replace(" ", "");
        if (SHORT_NAME_ENDINGS.stream().anyMatch(name::endsWith)) return 40;
        return TABLE_BASE;
    }

    // "여행 > 관광,명소 > 해수욕장,해변" → [해수욕장, 해변], "섬(내륙)" → [섬]
    private static List<String> kinds(String subtype) {
        String value = subtype == null ? "" : subtype.strip();
        String last = value.contains(">") ? value.substring(value.lastIndexOf('>') + 1) : value;
        return Arrays.stream(last.replaceAll("\\(.*?\\)", "").split("[,·\\s]+")).filter(s -> !s.isBlank()).toList();
    }
}
