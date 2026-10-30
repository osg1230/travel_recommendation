package com.travel.travel_recommendation.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProvincesTests {
    @Test void officialShortAndOldNamesBecomeOneName() {
        assertThat(Provinces.canonical("경상북도")).isEqualTo("경북");
        assertThat(Provinces.canonical("강원도")).isEqualTo("강원");
        assertThat(Provinces.canonical("전라북도")).isEqualTo("전북");
        assertThat(Provinces.canonical("광주광역시")).isEqualTo("전남광주");
        assertThat(Provinces.canonical("전남광주통합특별시")).isEqualTo("전남광주");
        assertThat(Provinces.canonical("강릉")).isNull();
        // 경기도 광주시와 헷갈리지 않도록 '광주시'는 시·도 이름으로 보지 않는다.
        assertThat(Provinces.canonical("광주시")).isNull();
    }

    // AI가 옛 이름·줄임말로 쓴 시·도도 여행지 지역명과 같은 정식 이름으로 바꾼다.
    @Test void officialNameIsTheCurrentFullName() {
        assertThat(Provinces.officialName("전라북도")).isEqualTo("전북특별자치도");
        assertThat(Provinces.officialName("전라남도")).isEqualTo("전남광주통합특별시");
        assertThat(Provinces.officialName("광주")).isEqualTo("전남광주통합특별시");
        assertThat(Provinces.officialName("강원도")).isEqualTo("강원특별자치도");
        assertThat(Provinces.officialName("경북")).isEqualTo("경상북도");
        assertThat(Provinces.officialName("부산")).isEqualTo("부산광역시");
        assertThat(Provinces.officialName("어딘가")).isNull();
    }

    // 카카오 주소 표기(경기·충북·전남광주통합특별시)와 DB 지역명(경기도·충청북도)을 같은 형태로 맞춘다.
    @Test void addressesFromDifferentSourcesCompareEqual() {
        assertThat(Provinces.normalizeAddress("경기도 수원시 팔달구")).isEqualTo(Provinces.normalizeAddress("경기  수원시 팔달구"));
        assertThat(Provinces.normalizeAddress("전라남도 여수시 돌산읍")).isEqualTo(Provinces.normalizeAddress("전남광주통합특별시 여수시 돌산읍"));
        assertThat(Provinces.normalizeAddress("부산광역시")).isEqualTo("부산");
        assertThat(Provinces.normalizeAddress("강원특별자치도 강릉시 경포로 365")).isEqualTo("강원 강릉시 경포로 365");
        assertThat(Provinces.normalizeAddress("어딘가 123")).isEqualTo("어딘가 123");
    }

    @Test void kakaoSearchWordComesFromTheRegionName() {
        assertThat(KakaoMapClient.searchWord("강원특별자치도 강릉시")).isEqualTo("강릉");
        assertThat(KakaoMapClient.searchWord("경기도 가평군")).isEqualTo("가평");
        assertThat(KakaoMapClient.searchWord("부산광역시")).isEqualTo("부산");
        assertThat(KakaoMapClient.searchWord("제주특별자치도")).isEqualTo("제주");
        assertThat(KakaoMapClient.searchWord("전남광주통합특별시 담양군")).isEqualTo("담양");
        assertThat(KakaoMapClient.searchWord("어딘가")).isNull();
        assertThat(KakaoMapClient.searchWord("")).isNull();
    }
}
