package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.PetCheck;
import com.travel.travel_recommendation.entity.Place;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class PetTravelTests {
    private static ConfirmedTravelConditions conditions(String original, String purpose) {
        return new ConfirmedTravelConditions("TEXT", original, "강릉", null, null, 2, null, 2, true, null, List.of(), "", true, purpose, null, false, List.of());
    }

    // 여행 목적이나 처음 입력한 문장에 반려동물이 있으면 동반 여행으로 본다.
    @Test void detectsPetTravelFromPurposeOrOriginalText() {
        assertThat(PetTravel.wanted(conditions("강아지랑 바다 보러 가요", null))).isTrue();
        assertThat(PetTravel.wanted(conditions("", "반려견 동반 산책"))).isTrue();
        assertThat(PetTravel.wanted(conditions("", "반려 동물과 휴식"))).isTrue();
        assertThat(PetTravel.wanted(conditions("가족이랑 바다", "휴식"))).isFalse();
        assertThat(PetTravel.wanted(null)).isFalse();
    }

    // 동반 구분 원문을 판정으로 나누고, 화면용 한 줄은 원문 줄바꿈을 없앤다.
    @Test void petInfoStatusAndDescriptionFollowSourceText() {
        var allowed = new PlacePetInfo("1", "전구역 동반가능", "전 견종 동반 가능", "목줄 착용", "- 맹견 입마개 필수\n- 배변봉투 지참", Instant.now());
        assertThat(allowed.status()).isEqualTo("ALLOWED");
        assertThat(new PetCheck(1L, "해변", allowed).describe()).isEqualTo("동반 가능 · 전 견종 동반 가능 · 목줄 착용 · - 맹견 입마개 필수 - 배변봉투 지참 (한국관광공사)");
        assertThat(new PlacePetInfo("1", "일부구역 동반가능", "", "", "", Instant.now()).status()).isEqualTo("PARTIAL");
        assertThat(new PlacePetInfo("1", "동반불가", "", "", "", Instant.now()).label()).isEqualTo("동반 불가");
        assertThat(PlacePetInfo.none("1").found()).isFalse();
        assertThat(new PetCheck(1L, "식당", PlacePetInfo.none("1")).describe()).startsWith("정보 없음 · 관광공사에 등록된 장소지만");
        assertThat(new PetCheck(1L, "식당", null).status()).isEqualTo("UNKNOWN");
    }

    // 장소 상세정보의 다른 값은 보존하고, 이름이 바뀌면 재사용하지 않으며, 7일이 지나면 다시 조회한다.
    @Test void detailsKeepOtherFieldsAndExpireAfterSevenDays() {
        var mapper = new JsonMapper();
        var data = new KakaoPlace("1", "ATTRACTION", "경포해변", "강원특별자치도 강릉시", "강원특별자치도 강릉시 창해로 514", "", 128.9, 37.8, "https://place.map.kakao.com/1", LocalDateTime.now());
        var entity = Place.fromKakao(data); entity.updateDetails("{\"foods\":[\"기존 정보\"]}");
        var info = new PlacePetInfo("129784", "전구역 동반가능", "전 견종", "목줄 착용", "", Instant.now());
        entity.updateDetails(PlacePetDetails.write(entity, info, mapper));
        assertThat(mapper.readTree(entity.getDetailsJson()).path("foods").get(0).asText()).isEqualTo("기존 정보");
        assertThat(PlacePetDetails.read(entity, mapper)).isEqualTo(info);
        assertThat(PlacePetDetails.fresh(info, Instant.now())).isTrue();
        assertThat(PlacePetDetails.fresh(info, Instant.now().plus(Duration.ofDays(8)))).isFalse();
        assertThat(PlacePetDetails.fresh(null, Instant.now())).isFalse();
        entity.refreshKakao(new KakaoPlace("1", "ATTRACTION", "바뀐 이름", data.region(), data.address(), "", data.longitude(), data.latitude(), data.url(), data.checkedAt()));
        assertThat(PlacePetDetails.read(entity, mapper)).isNull();
    }
}
