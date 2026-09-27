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

    @Test void onlyKnownProvinceCodesAreAccepted() {
        assertThat(FestivalService.province("12").orElseThrow().regionName()).isEqualTo("전남");
        assertThat(FestivalService.province("99")).isEmpty();
        assertThat(FestivalService.province(null)).isEmpty();
    }
}
