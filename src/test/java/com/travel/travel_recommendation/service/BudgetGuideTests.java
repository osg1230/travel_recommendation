package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.PlaceRepository;
import com.travel.travel_recommendation.service.BudgetGuide.StayTier;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class BudgetGuideTests {
    @Test void classifiesStayTypesWithoutGuessingUnknownOnes() {
        assertThat(StayTier.of("여행 > 숙박 > 야영,캠핑장","연곡해변 솔향기 캠핑장")).contains(StayTier.BUDGET);
        assertThat(StayTier.of("여행 > 숙박 > 게스트하우스","바다 게스트하우스")).contains(StayTier.BUDGET);
        assertThat(StayTier.of("여행 > 숙박 > 모텔","아무 모텔")).contains(StayTier.BUDGET);
        assertThat(StayTier.of("호텔","씨마크 호텔")).contains(StayTier.STANDARD);
        assertThat(StayTier.of("여행 > 숙박 > 펜션","솔숲 펜션")).contains(StayTier.STANDARD);
        assertThat(StayTier.of("여행 > 숙박 > 콘도,리조트","바다 리조트")).contains(StayTier.COMFORT);
        // 유형을 알 수 있는 낱말이 없으면 억지로 나누지 않는다.
        assertThat(StayTier.of("여행 > 숙박","무명 숙소")).isEmpty();
        assertThat(StayTier.of(null,null)).isEmpty();
    }

    @Test void nationalAverageIsPerTravelerPerTripFromOfficialSource() {
        assertThat(BudgetGuide.NATIONAL_AVERAGE.dayTripWon()).isEqualTo(69_000);
        assertThat(BudgetGuide.NATIONAL_AVERAGE.overnightWon()).isEqualTo(221_000);
        assertThat(BudgetGuide.NATIONAL_AVERAGE.sourceUrl()).startsWith("https://www.mcst.go.kr/");
    }

    private Place place(String type,String name,String subtype,String provider,LocalDateTime verifiedAt) {
        var place=mock(Place.class);
        when(place.getPlaceType()).thenReturn(type); when(place.getName()).thenReturn(name);
        when(place.getSubtype()).thenReturn(subtype); when(place.getSourceProvider()).thenReturn(provider);
        when(place.getVerifiedAt()).thenReturn(verifiedAt);
        return place;
    }

    @Test void groupsOnlyUsableAccommodationsByTier() {
        var repository=mock(PlaceRepository.class);
        var now=KoreaTime.now();
        // 목 객체는 when(...) 밖에서 먼저 만든다(스텁 안에서 만들면 Mockito가 오류를 낸다).
        var local=List.of(
            place("ACCOMMODATION","씨마크 호텔","호텔","SYSTEM",now.minusDays(10)),
            place("ACCOMMODATION","솔향기 캠핑장","여행 > 숙박 > 야영,캠핑장","KAKAO",now.minusHours(1)),
            // 24시간이 지난 카카오 후보와 유형을 알 수 없는 숙소, 숙소가 아닌 장소는 세지 않는다.
            place("ACCOMMODATION","오래된 모텔","여행 > 숙박 > 모텔","KAKAO",now.minusHours(30)),
            place("ACCOMMODATION","무명 숙소","여행 > 숙박","SYSTEM",now),
            place("ATTRACTION","경포대 호텔 전망대","누각","SYSTEM",now));
        when(repository.findLocalPlaces("강원특별자치도 강릉시")).thenReturn(local);
        var result=new BudgetGuideService(repository).tiers("강릉","강원특별자치도 강릉시");
        assertThat(result.destination()).isEqualTo("강릉");
        assertThat(result.tiers()).extracting(BudgetGuideService.TierGroup::tier).containsExactly(StayTier.BUDGET,StayTier.STANDARD,StayTier.COMFORT);
        assertThat(result.tiers()).extracting(BudgetGuideService.TierGroup::count).containsExactly(1,1,0);
        assertThat(result.tiers().getFirst().examples()).containsExactly("솔향기 캠핑장");
    }
}
