package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.RegionalFestival;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class FestivalServiceTests {
    private static final LocalDate TODAY = LocalDate.of(2026, 9, 27);

    private RegionalFestival festival(String id, LocalDate start, LocalDate end) {
        return new RegionalFestival(id, "축제" + id, start, end, "", "", "", "");
    }

    @Test void showsOngoingAndSoonFestivalsInStartOrderAndCachesPerDay() {
        var client = mock(TourApiClient.class);
        when(client.searchFestivals("43", TODAY)).thenReturn(List.of(
            festival("late", TODAY.plusDays(10), TODAY.plusDays(12)),
            festival("now", TODAY.minusDays(3), TODAY.plusDays(2)),
            festival("far", TODAY.plusDays(61), TODAY.plusDays(62)),       // 두 달 뒤라 뺀다
            festival("yearly", TODAY.minusDays(200), TODAY.plusDays(90)),  // 상설 행사라 뺀다
            festival("ended", TODAY.minusDays(5), TODAY.minusDays(1))));   // 이미 끝나 뺀다
        var service = new FestivalService(client, () -> TODAY);
        var chungbuk = FestivalService.province("43").orElseThrow();

        assertThat(service.findFestivals(chungbuk)).extracting(RegionalFestival::id).containsExactly("now", "late");
        assertThat(service.findFestival(chungbuk, "late")).isPresent();
        assertThat(service.findFestival(chungbuk, "far")).isEmpty();
        verify(client, times(1)).searchFestivals("43", TODAY);
    }

    // 15개까지 받아 6개씩 나누고, 없는 쪽 번호는 가장 가까운 쪽으로 맞춘다. 쪽을 넘겨도 API는 한 번만 부른다.
    @Test void keepsFifteenFestivalsInPagesOfSix() {
        var client = mock(TourApiClient.class);
        var many = new java.util.ArrayList<RegionalFestival>();
        for (int i = 0; i < 20; i++) many.add(festival(String.valueOf(i), TODAY.plusDays(i), TODAY.plusDays(i + 1)));
        when(client.searchFestivals("12", TODAY)).thenReturn(many);
        var service = new FestivalService(client, () -> TODAY);
        var jeonnam = FestivalService.province("12").orElseThrow();

        assertThat(service.findFestivals(jeonnam)).hasSize(15);
        var first = service.findFestivalPage(jeonnam, 1);
        assertThat(first.festivals()).extracting(RegionalFestival::id).containsExactly("0", "1", "2", "3", "4", "5");
        assertThat(first.totalPages()).isEqualTo(3);
        assertThat(first.hasPrevious()).isFalse();
        var last = service.findFestivalPage(jeonnam, 3);
        assertThat(last.festivals()).extracting(RegionalFestival::id).containsExactly("12", "13", "14");
        assertThat(last.hasNext()).isFalse();
        assertThat(service.findFestivalPage(jeonnam, 99).page()).isEqualTo(3);
        assertThat(service.findFestivalPage(jeonnam, 0).page()).isEqualTo(1);
        verify(client, times(1)).searchFestivals("12", TODAY);
    }

    // 축제 요청용 조회는 여행 기간과 겹치는 축제를 15개 제한 없이 돌려주고, 60일 뒤 여행도 찾는다. 상설 행사는 여기서도 뺀다.
    @Test void festivalRequestSearchUsesTripDatesWithoutTheFifteenLimit() {
        var client = mock(TourApiClient.class);
        LocalDate from = TODAY.plusDays(90), until = TODAY.plusDays(110);
        var many = new java.util.ArrayList<RegionalFestival>();
        for (int i = 0; i < 20; i++) many.add(festival(String.valueOf(i), from.plusDays(i), from.plusDays(i)));
        many.add(festival("after", until.plusDays(1), until.plusDays(2)));        // 여행이 끝난 뒤라 뺀다
        many.add(festival("yearly", from.minusDays(100), until.plusDays(10)));     // 상설 행사라 뺀다
        when(client.searchFestivals("12", from)).thenReturn(many);
        var service = new FestivalService(client, () -> TODAY);
        var jeonnam = FestivalService.province("12").orElseThrow();

        assertThat(service.findFestivalsBetween(jeonnam, from, until)).hasSize(20).extracting(RegionalFestival::id).doesNotContain("after", "yearly");
        service.findFestivalsBetween(jeonnam, from, until);
        verify(client, times(1)).searchFestivals("12", from);
    }

    @Test void onlyKnownProvinceCodesAreAccepted() {
        assertThat(FestivalService.province("12").orElseThrow().regionName()).isEqualTo("전남");
        assertThat(FestivalService.province("99")).isEmpty();
        assertThat(FestivalService.province(null)).isEmpty();
    }
}
