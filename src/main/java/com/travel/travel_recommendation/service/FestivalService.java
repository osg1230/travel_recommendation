package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.RegionalFestival;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 홈 화면의 '지역 축제'에 보여 줄 축제를 관광공사 TourAPI에서 시·도별로 찾는다.
 * 축제 정보는 DB에 저장하지 않고 서버 메모리에 몇 시간만 보관해 같은 시·도를 다시 열 때 API를 부르지 않는다.
 */
@Service
public class FestivalService {
    /** 화면의 시·도 탭. code는 관광공사 법정동 시·도 코드, regionName은 조건 입력 화면의 지역 키워드 이름이다. */
    public record Province(String code, String label, String regionName) {}

    // 2026-07-01 광주와 전남이 전남광주통합특별시(코드 12)로 합쳐져 한 탭으로 둔다. 조건 입력은 기존 '전남' 지역으로 채운다.
    public static final List<Province> PROVINCES = List.of(
        new Province("11", "서울", "서울"), new Province("41", "경기", "경기"), new Province("28", "인천", "인천"),
        new Province("51", "강원", "강원"), new Province("43", "충북", "충북"), new Province("44", "충남", "충남"),
        new Province("30", "대전", "대전"), new Province("36110", "세종", "세종"), new Province("52", "전북", "전북"),
        new Province("12", "광주·전남", "전남"), new Province("47", "경북", "경북"), new Province("27", "대구", "대구"),
        new Province("48", "경남", "경남"), new Province("26", "부산", "부산"), new Province("31", "울산", "울산"),
        new Province("50", "제주", "제주"));
    /** 오늘부터 이 기간 안에 시작하는 축제만 보여 준다. */
    static final int LOOKAHEAD_DAYS = 60;
    /** 이보다 긴 행사는 상설 전시·연중 행사로 보고 뺀다. 축제 목록 앞쪽을 차지하기 때문이다. */
    static final int MAX_EVENT_DAYS = 62;
    static final int MAX_SHOWN = 6;

    private final TourApiClient tourApi;
    private final Supplier<LocalDate> today;
    private final ExpiringCache<String, List<RegionalFestival>> cache = new ExpiringCache<>(Duration.ofHours(6), 40);

    @Autowired
    public FestivalService(TourApiClient tourApi) {
        this(tourApi, () -> LocalDate.now(ZoneId.of("Asia/Seoul")));
    }

    // 테스트에서만 오늘 날짜를 고정한다.
    FestivalService(TourApiClient tourApi, Supplier<LocalDate> today) {
        this.tourApi = tourApi;
        this.today = today;
    }

    public static Optional<Province> province(String code) {
        return PROVINCES.stream().filter(p -> p.code().equals(code)).findFirst();
    }

    public boolean configured() { return tourApi.configured(); }

    public LocalDate today() { return today.get(); }

    /** 시·도의 진행 중이거나 곧 열리는 축제를 시작일 순으로 최대 6개 돌려준다. 조회 실패는 RecommendationException으로 전달한다. */
    public List<RegionalFestival> findFestivals(Province province) {
        LocalDate date = today.get();
        // 날짜가 바뀌면 끝난 축제가 빠지도록 키에 날짜를 넣는다.
        return cache.get(province.code() + ":" + date, () -> tourApi.searchFestivals(province.code(), date).stream()
            .filter(f -> !f.endDate().isBefore(date) && !f.startDate().isAfter(date.plusDays(LOOKAHEAD_DAYS)))
            .filter(f -> ChronoUnit.DAYS.between(f.startDate(), f.endDate()) < MAX_EVENT_DAYS)
            .sorted(Comparator.comparing(RegionalFestival::startDate).thenComparing(RegionalFestival::title))
            .limit(MAX_SHOWN).toList());
    }

    /** 화면에서 고른 축제를 서버에서 다시 찾는다. 요청에 담긴 날짜를 믿지 않기 위해서다. */
    public Optional<RegionalFestival> findFestival(Province province, String festivalId) {
        return findFestivals(province).stream().filter(f -> f.id().equals(festivalId)).findFirst();
    }
}
