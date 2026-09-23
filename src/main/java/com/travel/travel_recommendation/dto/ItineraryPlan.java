package com.travel.travel_recommendation.dto;

import com.travel.travel_recommendation.service.KakaoMapClient.Transport;
import java.time.*;
import java.util.*;

/** 작업 입력과 검증된 일정 초안. 장소 정보는 서버 후보 스냅샷을 사용한다. */
public final class ItineraryPlan {
    private ItineraryPlan() {}
    /**
     * reserve는 일정에 쓰려고 고른 관광지(places.attractions)가 섬처럼 갈 수 없어 빠질 때 대신 넣거나,
     * 고른 관광지를 다 넣고도 하루 시간이 남을 때 더 넣는 예비 관광지다. 쓰지 않은 예비 관광지는 결과에 적지 않는다.
     */
    public record Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                        LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                        Constraints constraints, List<PlaceCandidate> reserve) {
        public Input { reserve = reserve == null ? List.of() : List.copyOf(reserve); }
        // 처음 만드는 초안과 이전 작업 JSON은 수정 정보·조건 구분·예비 관광지 없이 그대로 사용한다.
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, null, null, null);
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, null, null);
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                     Constraints constraints) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, null);
        }
        /** 고른 관광지와 예비 관광지 전체. 일정에 들어간 관광지가 어느 쪽이든 찾을 수 있다. */
        public List<PlaceCandidate> attractionsWithReserve() {
            var all = new ArrayList<>(places.attractions()); all.addAll(reserve);
            return List.copyOf(all);
        }
    }
    /**
     * 필수·선호 조건 구분(SFR-02-07). 필수 장소는 반드시 넣고, 하루 방문 수는 required이면 반드시 지키고
     * 아니면 가능한 경우에만 반영한다. 예산 기준·포함 비용은 가격 자료가 없어 확인 필요 표시에만 쓴다.
     */
    /** centerPlaceIds는 날마다 일정의 중심으로 삼을 관광지(느슨한 조건, 1일차부터 차례로)다. 이전 작업 JSON에는 없다. */
    public record Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                              String budgetBasis, List<String> budgetIncludes, String stayTier, List<Long> centerPlaceIds) {
        public Constraints {
            requiredPlaceIds = requiredPlaceIds == null ? List.of() : List.copyOf(requiredPlaceIds);
            budgetIncludes = budgetIncludes == null ? List.of() : List.copyOf(budgetIncludes);
            centerPlaceIds = centerPlaceIds == null ? List.of() : List.copyOf(centerPlaceIds);
        }
        public Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                           String budgetBasis, List<String> budgetIncludes, String stayTier) {
            this(requiredPlaceIds, dailyVisits, dailyVisitsRequired, budgetBasis, budgetIncludes, stayTier, null);
        }
        // stayTier는 예산을 입력하지 않았을 때 고르는 숙소 예산대(SFR-03-03)다. 이전 작업 JSON에는 없다.
        public Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                           String budgetBasis, List<String> budgetIncludes) {
            this(requiredPlaceIds, dailyVisits, dailyVisitsRequired, budgetBasis, budgetIncludes, null);
        }
    }
    public record PlaceRef(Long placeId, String name) {}
    /**
     * 기존 초안을 바탕으로 만든 수정안(SFR-04-07·08-04). 원본 초안은 바꾸지 않는다.
     * kept는 사용자가 유지한 관광지이며 시각 고정이 아니라 장소 유지만 뜻한다.
     */
    /**
     * unavailablePlaces는 수정 요청에 이름이 나왔지만 확인된 교체 후보에 없어 비슷한 곳으로 바꾼 장소다.
     * 이 값이 생기기 전의 작업 JSON에는 없으므로 null이면 빈 목록으로 본다.
     */
    public record Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                           String reason, Instant requestedAt, String request, String explanation, List<String> unavailablePlaces) {
        public Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                        String reason, Instant requestedAt, String request, String explanation) {
            this(sourceJobId, kept, replaced, added, reason, requestedAt, request, explanation, List.of());
        }
        // 직접 고른 수정안과 이전 작업 JSON은 자연어 요청·AI 설명 없이 그대로 사용한다.
        public Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                        String reason, Instant requestedAt) {
            this(sourceJobId, kept, replaced, added, reason, requestedAt, null, null);
        }
        public List<String> missingPlaces() { return unavailablePlaces == null ? List.of() : unavailablePlaces; }
    }
    public record Item(String type, Long placeId, String name, LocalTime start, LocalTime end,
                       String note, String mapUrl, String sourceUrl, Integer travelSeconds, Instant checkedAt) {
        /** 화면용 조회 시각(한국 시간, 분 단위). 저장 JSON에는 들어가지 않는다. */
        public String checkedAtText() {
            return checkedAt == null ? null : java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm")
                .withZone(java.time.ZoneId.of("Asia/Seoul")).format(checkedAt);
        }
    }
    public record Day(int number, LocalDate date, List<Item> items) {}
    /** AI가 작성한 보조 안내. 서버가 만든 시각·장소·순서를 바꾸지 않으며 검증 완료를 뜻하지 않는다. */
    public record Assist(String summary, List<DayAdvice> days, Instant createdAt) {}
    public record DayAdvice(int number, String advice) {}
    /**
     * 주변 지역 추천 결과(SFR-07). decision은 추천 여부 판단이고 regions는 기준 시간 안의 지역만 담는다.
     * 일정에 끼워 넣지 않는 참고 추천이며, 이동시간은 조회 시점의 예상값이다.
     */
    public record Nearby(String decision, String reason, String basis, List<NearbyRegion> regions, String notice, Instant checkedAt) {
        public Nearby { regions = regions == null ? List.of() : List.copyOf(regions); }
    }
    /** minutes는 basis(자차 또는 직행 시외버스)의 편도 예상 시간이다. bus는 시외버스 기준일 때만 있다. */
    public record NearbyRegion(Long placeId, String name, String region, String description, String sourceUrl,
                               int minutes, String route, BusInfo bus, List<NearbyAttraction> attractions) {
        public NearbyRegion { attractions = attractions == null ? List.of() : List.copyOf(attractions); }
    }
    /** 오늘 운행하는 직행 시외버스 요약. 여행 당일 시간표와 다를 수 있다(SFR-06-06). */
    public record BusInfo(LocalDate serviceDate, int trips, LocalTime firstDeparture, LocalTime lastDeparture, Integer minCharge) {}
    public record NearbyAttraction(Long placeId, String name, String subtype, String description, String address, String sourceUrl) {}
    /**
     * 숙소 추천(SFR-05-02·03). 관광지와의 평균 직선거리가 짧은 순서로 후보를 보여 주고, 첫 후보를 일정의 숙소로 쓴다.
     * 직선거리는 동선 판단용 근사값이며 실제 이동시간·가격·예약 가능 여부를 뜻하지 않는다.
     */
    public record Lodging(Long chosenPlaceId, String reason, List<StayOption> options) {
        public Lodging { options = options == null ? List.of() : List.copyOf(options); }
    }
    public record StayOption(Long placeId, String name, String subtype, String address, double averageKilometers,
                             String sourceUrl, String mapUrl) {}
    /**
     * AI 웹 검색으로 찾은 숙소·음식점 참고 가격 하나. 날짜·인원·메뉴에 따라 실제 가격은 다르다.
     * status: VERIFIED(출처 페이지에서 상호와 가격 숫자를 서버가 확인), UNVERIFIED(검색 결과에 있는 출처지만 페이지 내용은 확인하지 못함),
     * NOT_FOUND(가격을 찾지 못했거나 근거를 믿을 수 없어 쓰지 않음). NOT_FOUND는 가격·출처가 비어 있다.
     */
    /** checkedAt은 이 장소를 실제로 검색한 시각이다. 장소에 저장해 둔 결과를 다시 쓰면 묶음의 조회 시각보다 앞선다. */
    public record PriceQuote(Long placeId, String name, String kind, String status, String priceText, Integer minWon,
                             Integer maxWon, String basis, String sourceUrl, Instant checkedAt) {
        public PriceQuote(Long placeId, String name, String kind, String status, String priceText, Integer minWon,
                          Integer maxWon, String basis, String sourceUrl) {
            this(placeId, name, kind, status, priceText, minWon, maxWon, basis, sourceUrl, null);
        }
        public boolean found() { return !"NOT_FOUND".equals(status); }
        /** 이번 조회 묶음보다 10분 넘게 앞서 검색한 결과, 즉 장소에 저장해 둔 가격을 다시 쓴 경우다. */
        public boolean reusedBefore(Instant batch) {
            return checkedAt != null && batch != null && checkedAt.isBefore(batch.minus(java.time.Duration.ofMinutes(10)));
        }
        public PriceQuote checkedAt(Instant at) {
            return new PriceQuote(placeId, name, kind, status, priceText, minWon, maxWon, basis, sourceUrl, at);
        }
    }
    /** 결과 화면에서 버튼을 눌러 조회한 참고 가격 묶음. checkedAt은 조회 시각이다. */
    public record Prices(List<PriceQuote> quotes, Instant checkedAt) {
        public Prices { quotes = quotes == null ? List.of() : List.copyOf(quotes); }
    }
    /**
     * skipped는 일정에 쓰려고 골랐지만 하루 시간 안에 넣지 못해 뺀 관광지다. 필수·유지 장소는 여기에 들어가지 않는다.
     * prices는 완성된 초안에 나중에 붙이는 참고 가격이며 일정의 시각·장소를 바꾸지 않는다.
     */
    public record Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby,
                         Lodging lodging, List<PlaceRef> skipped, Prices prices) {
        public Result { skipped = skipped == null ? List.of() : List.copyOf(skipped); }
        /** 화면용: 이동시간을 처음 조회한 시각(한국 시간). 구간마다 적지 않고 일정 위에 한 번만 보여 준다. 저장 JSON에는 들어가지 않는다. */
        public String routesCheckedAtText() {
            return days.stream().flatMap(d -> d.items().stream()).filter(i -> i.checkedAt() != null)
                .min(java.util.Comparator.comparing(Item::checkedAt)).map(Item::checkedAtText).orElse(null);
        }
        // 보조 안내를 붙이기 전의 초안과 이전 작업 JSON은 assist·nearby·lodging·skipped·prices 없이 그대로 사용한다.
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt) {
            this(destination, days, transport, createdAt, null, null, null, null, null);
        }
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist) {
            this(destination, days, transport, createdAt, assist, null, null, null, null);
        }
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby) {
            this(destination, days, transport, createdAt, assist, nearby, null, null, null);
        }
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby,
                      Lodging lodging) {
            this(destination, days, transport, createdAt, assist, nearby, lodging, null, null);
        }
        public Result withAssist(Assist assist) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices); }
        public Result withNearby(Nearby nearby) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices); }
        public Result withLodging(Lodging lodging) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices); }
        public Result withSkipped(List<PlaceRef> skipped) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices); }
        public Result withPrices(Prices prices) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices); }
    }
    public record View(UUID id, String status, String message, boolean pending, Input input, Result result) {}
}
