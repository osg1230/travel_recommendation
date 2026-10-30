package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 시·도 이름을 한 가지로 맞춘다. 같은 시·도라도 "강원특별자치도"와 "강원", "경상북도"와 "경북"처럼 다르게 쓰이고,
 * 2026-07-01 광주광역시와 전라남도가 전남광주통합특별시로 합쳐져 예전 주소와 새 주소가 함께 남아 있다.
 * 카카오 주소도 "경기 수원시", "전남광주통합특별시 여수시"처럼 시·도마다 표기가 달라 비교 전에 이 이름으로 바꾼다.
 */
public final class Provinces {
    private Provinces() {}

    /** 대표 이름 → 같은 시·도를 뜻하는 이름들. 여행 조건의 시·도 선택값(서울·강원 등)도 여기에 들어 있다. */
    private static final Map<String, List<String>> NAMES = new LinkedHashMap<>();
    static {
        NAMES.put("서울", List.of("서울", "서울시", "서울특별시"));
        NAMES.put("부산", List.of("부산", "부산시", "부산광역시"));
        NAMES.put("대구", List.of("대구", "대구시", "대구광역시"));
        NAMES.put("인천", List.of("인천", "인천시", "인천광역시"));
        NAMES.put("대전", List.of("대전", "대전시", "대전광역시"));
        NAMES.put("울산", List.of("울산", "울산시", "울산광역시"));
        NAMES.put("세종", List.of("세종", "세종시", "세종특별자치시"));
        NAMES.put("경기", List.of("경기", "경기도"));
        NAMES.put("강원", List.of("강원", "강원도", "강원특별자치도"));
        NAMES.put("충북", List.of("충북", "충청북도"));
        NAMES.put("충남", List.of("충남", "충청남도"));
        NAMES.put("전북", List.of("전북", "전라북도", "전북특별자치도"));
        // "광주시"는 경기도 광주시와 헷갈리므로 넣지 않는다.
        NAMES.put("전남광주", List.of("전남광주", "전남광주통합특별시", "전남광주특별시", "전남", "전라남도", "광주", "광주광역시"));
        NAMES.put("경북", List.of("경북", "경상북도"));
        NAMES.put("경남", List.of("경남", "경상남도"));
        NAMES.put("제주", List.of("제주", "제주도", "제주특별자치도"));
    }

    /** 시·도 이름의 대표 이름. 시·도 이름이 아니면 null이다. 예) "경상북도" → "경북", "광주" → "전남광주". */
    public static String canonical(String name) {
        if (name == null) return null;
        for (var entry : NAMES.entrySet()) if (entry.getValue().contains(name)) return entry.getKey();
        return null;
    }

    /**
     * 지금 쓰는 정식 시·도 이름. 관광공사 법정동 코드·여행지 지역 이름과 같은 표기다. 시·도 이름이 아니면 null이다.
     * 예) "전라북도" → "전북특별자치도", "전라남도"·"광주" → "전남광주통합특별시", "경북" → "경상북도".
     */
    public static String officialName(String name) {
        String key = canonical(name);
        if (key == null) return null;
        return "전남광주".equals(key) ? "전남광주통합특별시" : NAMES.get(key).getLast();
    }

    /** 대표 이름으로 같은 시·도를 뜻하는 이름 목록을 얻는다. */
    public static List<String> names(String canonical) {
        return NAMES.getOrDefault(canonical, List.of());
    }

    /** 주소 첫 단어의 시·도 이름을 대표 이름으로 바꾸고 공백을 하나로 맞춘다. 예) "경기도 수원시 팔달구" → "경기 수원시 팔달구". */
    public static String normalizeAddress(String address) {
        String text = address.strip().replaceAll("\\s+", " ");
        int space = text.indexOf(' ');
        String first = space < 0 ? text : text.substring(0, space);
        String province = canonical(first);
        if (province == null) return text;
        return space < 0 ? province : province + text.substring(space);
    }
}
