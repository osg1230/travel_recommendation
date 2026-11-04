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
    /**
     * evening은 숙박하는 날 저녁 식사 뒤에 들를 저녁 관광 후보(야경·산책·야시장 등)이고, home은 첫날 집 출발·마지막 날 귀가 설정이다.
     * 둘 다 이전 작업 JSON에는 없으므로 없으면 저녁 관광·집 이동 없이 만든다.
     */
    /** choices는 수정 요청으로 정한 식당·숙소 선택이다(처음 만든 초안과 이전 작업 JSON에는 없다). */
    /** picking은 관광지를 고르기 전 후보와 AI가 순서를 정했는지 여부다(이전 작업 JSON에는 없다). */
    public record Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                        LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                        Constraints constraints, List<PlaceCandidate> reserve, List<PlaceCandidate> evening, HomeTrip home,
                        Choices choices, Picking picking) {
        public Input {
            reserve = reserve == null ? List.of() : List.copyOf(reserve);
            evening = evening == null ? List.of() : List.copyOf(evening);
            choices = choices == null ? Choices.NONE : choices;
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                     Constraints constraints, List<PlaceCandidate> reserve, List<PlaceCandidate> evening, HomeTrip home,
                     Choices choices) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, null);
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                     Constraints constraints, List<PlaceCandidate> reserve, List<PlaceCandidate> evening, HomeTrip home) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, null);
        }
        public Input(UUID recommendationId, PlaceCandidateView places, ConfirmedTravelConditions conditions,
                     LocalTime start, LocalTime end, Transport transport, int visitMinutes, Revision revision,
                     Constraints constraints, List<PlaceCandidate> reserve) {
            this(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, null, null, null);
        }
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
        /** 고른 관광지·예비 관광지·저녁 관광 후보 전체. 일정에 들어간 관광지가 어느 쪽이든 찾을 수 있다. */
        public List<PlaceCandidate> attractionsWithReserve() {
            var all = new ArrayList<>(places.attractions()); all.addAll(reserve);
            evening.stream().filter(p -> !all.contains(p)).forEach(all::add);
            return List.copyOf(all);
        }
        public Input withPlaces(PlaceCandidateView places) {
            return new Input(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, picking);
        }
        public Input withEvening(List<PlaceCandidate> evening) {
            return new Input(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, picking);
        }
        public Input withHome(HomeTrip home) {
            return new Input(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, picking);
        }
        public Input withChoices(Choices choices) {
            return new Input(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, picking);
        }
        public Input withPicking(Picking picking) {
            return new Input(recommendationId, places, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices, picking);
        }
        /**
         * AI가 정한 순서로 다시 고른 관광지·예비 관광지·저녁 관광 후보로 바꾼다. 고르기 전 후보는 더 쓰지 않으므로 비운다.
         * '다른 관광지로 다시 만들기'의 이전 초안 기록(previous·previousJobId)은 결과 화면의 비교에 쓰므로 그대로 둔다.
         * night는 저녁 관광 후보 맨 앞에 둔 AI 밤 명소의 ID, activities는 AI가 고른 관광지별 주된 활동이다.
         */
        public Input withPicked(List<PlaceCandidate> attractions, List<PlaceCandidate> reserve, List<PlaceCandidate> evening, List<Long> night,
                                Map<Long, String> activities) {
            var view = new PlaceCandidateView(places.jobId(), places.destination(), places.durationDays(), places.accommodationNeeded(),
                attractions, places.accommodations(), places.restaurants());
            return new Input(recommendationId, view, conditions, start, end, transport, visitMinutes, revision, constraints, reserve, evening, home, choices,
                new Picking(List.of(), true, picking == null ? List.of() : picking.previous(), picking == null ? null : picking.previousJobId(), night,
                    activities));
        }
        public Input withPicked(List<PlaceCandidate> attractions, List<PlaceCandidate> reserve, List<PlaceCandidate> evening, List<Long> night) {
            return withPicked(attractions, reserve, evening, night, Map.of());
        }
        /** 관광지의 주된 활동. AI가 고른 값이 없으면 분류 이름의 마지막 단계(예: 해변)를 쓴다. */
        public String activityOf(PlaceCandidate place) {
            String activity = picking == null ? null : picking.activities().get(place.placeId());
            return activity != null ? activity : com.travel.travel_recommendation.service.TripConstraints.kind(place);
        }
        /** AI가 밤에 둘러보기 좋다고 꼽아 저녁 관광으로 아껴 둔 관광지 ID. 없으면 빈 목록이다. */
        public List<Long> nightPlaceIds() { return picking == null ? List.of() : picking.night(); }
        /** '다른 관광지로 다시 만들기'로 만든 초안이면 이전 초안의 작업 ID, 아니면 null이다. */
        public UUID previousJobId() { return picking == null ? null : picking.previousJobId(); }
        /** AI가 정한 순서로 관광지를 골랐는지. */
        public boolean pickedByAi() { return picking != null && picking.byAi(); }
        private int dayCount() { return conditions == null || conditions.durationDays() == null ? 1 : conditions.durationDays(); }
        /** n일차 관광을 시작하는 시각. 집 출발을 정했으면 첫날은 집에서 출발해 여행지에 도착한 시각이다. */
        public LocalTime dayStart(int day) { return home != null && day == 1 ? home.arrivalAtDestination() : start; }
        /** n일차 관광을 마치는 시각. 귀가 시각을 정했으면 마지막 날은 집에 도착하도록 여행지를 떠나는 시각이다. */
        public LocalTime dayEnd(int day) { return home != null && day == dayCount() ? home.leaveDestination() : end; }
    }
    /**
     * 집 출발·귀가(SFR 일정 초안 보완, 2026-09-24 결정). 첫날은 departAt에 origin에서 출발하고 마지막 날은 arriveBy까지 returnPlace에 도착한다.
     * minutes·returnMinutes는 작업을 만들 때 조회하거나 사용자가 입력한 편도 이동시간(분)이고, basis·returnBasis는 그 시간을 얻은 방법이다.
     * 좌표는 저장하지 않고 화면에 보여 줄 이름(시·구 단위)만 둔다. 돌아갈 곳을 따로 정하지 않았으면(이전 작업 JSON 포함) 출발지와 같다.
     */
    public record HomeTrip(String origin, LocalTime departAt, LocalTime arriveBy, int minutes, String basis, Instant checkedAt,
                           String returnPlace, Integer returnMinutes, String returnBasis) {
        public HomeTrip {
            if (returnPlace == null || returnPlace.isBlank()) returnPlace = origin;
            if (returnMinutes == null) returnMinutes = minutes;
            if (returnBasis == null) returnBasis = basis;
        }
        /** 출발지로 돌아오는 경우. */
        public HomeTrip(String origin, LocalTime departAt, LocalTime arriveBy, int minutes, String basis, Instant checkedAt) {
            this(origin, departAt, arriveBy, minutes, basis, checkedAt, null, null, null);
        }
        /** 편도 이동에 더하는 여유(분). 장소 사이 이동과 같다. */
        public static final int SPARE_MINUTES = 10;
        /** 여행지에 도착하는 시각. 10분 단위로 올린다. */
        public LocalTime arrivalAtDestination() {
            int at = departAt.toSecondOfDay() / 60 + minutes + SPARE_MINUTES;
            return LocalTime.ofSecondOfDay(Math.min(23 * 60 + 50, (at + 9) / 10 * 10) * 60L);
        }
        /** 제시간에 집에 도착하려면 여행지를 떠나야 하는 시각. 10분 단위로 내린다. */
        public LocalTime leaveDestination() {
            int at = arriveBy.toSecondOfDay() / 60 - returnMinutes - SPARE_MINUTES;
            return LocalTime.ofSecondOfDay(Math.max(0, at / 10 * 10) * 60L);
        }
    }
    /**
     * 관광지를 고르는 과정의 기록. pool은 일정 작업을 만들 때의 관광지 후보 전체로, 작업이 시작되기 전에 AI가 순서를 정해
     * 다시 고를 때 쓴다(다시 고른 뒤에는 비운다). byAi는 지금 고른 관광지가 AI가 정한 순서를 따른 것인지다.
     */
    /** previous는 '다른 관광지로 다시 만들기'에서 직전 초안에 넣었던 관광지 ID다. AI가 순서를 정한 뒤에도 뒤로 보낸다. 이전 JSON에는 없다. */
    /** previousJobId는 '다른 관광지로 다시 만들기'의 직전 초안 작업 ID다. 결과 화면에서 이전 초안 링크와 비교에 쓴다. 이전 JSON에는 없다. */
    /**
     * night는 AI가 낮보다 밤에 둘러보기 좋다고 꼽아 낮 일정에서 빼고 저녁 관광 후보 맨 앞에 둔 관광지 ID다(2026-10-31).
     * 일정 생성은 저녁 관광에서 이곳을 먼저 본다. 이전 JSON에는 없다.
     */
    /**
     * activities는 관광지 ID별로 AI가 고른 주된 활동(예: 걷기·풍경)이다(2026-11-03). 일정 생성은 이 값으로 같은 활동이 이어지거나
     * 하루에 몰리지 않게 하고, 값이 없는 곳은 분류 이름(TripConstraints.kind)으로 본다. 이전 JSON에는 없다.
     */
    public record Picking(List<PlaceCandidate> pool, boolean byAi, List<Long> previous, UUID previousJobId, List<Long> night,
                          Map<Long, String> activities) {
        public Picking {
            pool = pool == null ? List.of() : List.copyOf(pool);
            previous = previous == null ? List.of() : List.copyOf(previous);
            night = night == null ? List.of() : List.copyOf(night);
            activities = activities == null ? Map.of() : Map.copyOf(activities);
        }
        public Picking(List<PlaceCandidate> pool, boolean byAi, List<Long> previous, UUID previousJobId, List<Long> night) {
            this(pool, byAi, previous, previousJobId, night, null);
        }
        public Picking(List<PlaceCandidate> pool, boolean byAi, List<Long> previous, UUID previousJobId) { this(pool, byAi, previous, previousJobId, null); }
        public Picking(List<PlaceCandidate> pool, boolean byAi, List<Long> previous) { this(pool, byAi, previous, null); }
        public Picking(List<PlaceCandidate> pool, boolean byAi) { this(pool, byAi, List.of()); }
    }
    /**
     * 필수·선호 조건 구분(SFR-02-07). 필수 장소는 반드시 넣고, 하루 방문 수는 required이면 반드시 지키고
     * 아니면 가능한 경우에만 반영한다. 예산 기준·포함 비용(budgetBasis·budgetIncludes)은 2026-10-10부터 입력받지 않으며
     * 이전 작업 JSON을 읽기 위해서만 남겨 둔다(값이 있어도 쓰지 않는다).
     */
    /**
     * centerPlaceIds는 날마다 일정의 중심으로 삼을 관광지(느슨한 조건, 1일차부터 차례로)다. 이전 작업 JSON에는 없다.
     * festival은 필수 장소 중 여행 기간에 축제가 열리는 곳의 안내 정보다. 축제를 넣지 않은 일정과 이전 작업 JSON에는 없다.
     */
    public record Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                              String budgetBasis, List<String> budgetIncludes, String stayTier, List<Long> centerPlaceIds,
                              FestivalVisit festival) {
        public Constraints {
            requiredPlaceIds = requiredPlaceIds == null ? List.of() : List.copyOf(requiredPlaceIds);
            budgetIncludes = budgetIncludes == null ? List.of() : List.copyOf(budgetIncludes);
            centerPlaceIds = centerPlaceIds == null ? List.of() : List.copyOf(centerPlaceIds);
        }
        public Constraints(List<Long> requiredPlaceIds, Integer dailyVisits, boolean dailyVisitsRequired,
                           String budgetBasis, List<String> budgetIncludes, String stayTier, List<Long> centerPlaceIds) {
            this(requiredPlaceIds, dailyVisits, dailyVisitsRequired, budgetBasis, budgetIncludes, stayTier, centerPlaceIds, null);
        }
        public Constraints withFestival(FestivalVisit visit) {
            return new Constraints(requiredPlaceIds, dailyVisits, dailyVisitsRequired, budgetBasis, budgetIncludes, stayTier, centerPlaceIds, visit);
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
    /** 여행 기간에 열리는 축제를 일정에 넣을 때의 정보. placeId는 축제가 열리는 관광지, day는 축제를 볼 수 있는 첫 여행 날(1부터)이다. */
    public record FestivalVisit(Long placeId, int day, String title, String period) {}
    /**
     * 수정 요청으로 정한 식당·숙소 선택. avoidRestaurants는 쓰지 않을 식당, preferRestaurants는 가까우면 먼저 쓸 식당,
     * stayPlaceId는 고정할 숙소다. 모두 확인된 후보(places) 안의 장소만 쓴다.
     */
    public record Choices(List<Long> avoidRestaurants, List<Long> preferRestaurants, Long stayPlaceId) {
        public static final Choices NONE = new Choices(List.of(), List.of(), null);
        public Choices {
            avoidRestaurants = avoidRestaurants == null ? List.of() : List.copyOf(avoidRestaurants);
            preferRestaurants = preferRestaurants == null ? List.of() : List.copyOf(preferRestaurants);
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
    /**
     * choiceNotices는 원래 고른 식당·숙소를 지금 다시 확인하지 못해 다른 곳으로 고른 경우의 안내 문장이다.
     * 이 값이 생기기 전의 작업 JSON에는 없으므로 null이면 빈 목록으로 본다.
     */
    /**
     * keptDays는 관광지를 빼기만 한 수정안에서 날짜별로 그대로 둘 일정이다(2026-11-02). 관광지를 뺐다고 다른 날의 일정까지 바뀌지 않게 한다.
     * 다른 수정안과 이 값이 생기기 전의 작업 JSON에는 없으며(null), 그때는 일정을 처음부터 다시 배치한다.
     */
    public record Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                           String reason, Instant requestedAt, String request, String explanation, List<String> unavailablePlaces,
                           List<String> choiceNotices, List<KeptDay> keptDays) {
        public Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                        String reason, Instant requestedAt, String request, String explanation, List<String> unavailablePlaces,
                        List<String> choiceNotices) {
            this(sourceJobId, kept, replaced, added, reason, requestedAt, request, explanation, unavailablePlaces, choiceNotices, null);
        }
        public Revision(UUID sourceJobId, List<PlaceRef> kept, List<PlaceRef> replaced, List<PlaceRef> added,
                        String reason, Instant requestedAt, String request, String explanation, List<String> unavailablePlaces) {
            this(sourceJobId, kept, replaced, added, reason, requestedAt, request, explanation, unavailablePlaces, null);
        }
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
        public List<String> choiceChanges() { return choiceNotices == null ? List.of() : choiceNotices; }
        /** n일차에 그대로 둘 일정. 없으면 null이다. */
        public KeptDay keptDay(int day) { return keptDays == null || day > keptDays.size() ? null : keptDays.get(day - 1); }
    }
    /** 빼기 수정안에서 하루에 그대로 둘 낮 관광지·식당(둘 다 방문 순서대로)과 저녁 관광(없으면 null)이다. */
    public record KeptDay(List<Long> visits, List<Long> meals, Long evening) {}
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
    /**
     * placeReasons는 장소 ID → AI가 쓴 한 문장이다. 2026-10-31부터는 관광지에서 해 볼 만한 활동(관광공사 소개문 근거)이고
     * 관광 항목 메모에 'AI 추천 활동' 줄로 더한다. 그 전 JSON은 "왜 가 볼 만한지" 문장('AI 설명')이며, 더 이전 JSON에는 없다.
     */
    public record Assist(String summary, List<DayAdvice> days, Instant createdAt, java.util.Map<Long, String> placeReasons) {
        public Assist { placeReasons = placeReasons == null ? java.util.Map.of() : java.util.Map.copyOf(placeReasons); }
        public Assist(String summary, List<DayAdvice> days, Instant createdAt) { this(summary, days, createdAt, null); }
    }
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
     * 숙소 추천(SFR-05-02·03). 관광지와의 평균 직선거리가 짧은 순서로 후보를 보여 주되, 가장 가까운 곳과 2km 안쪽 차이의
     * 관광공사 인기 숙소가 있으면 그곳을 앞에 둔다. options의 첫 후보가 일정의 숙소이고, reason은 일정이 정해진 뒤 오가는 시간으로 적는다.
     * 직선거리는 동선 판단용 근사값이며 가격·예약 가능 여부를 뜻하지 않는다.
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
    /** 결과 화면에서 버튼을 눌러 조회한 참고 가격 묶음. checkedAt은 조회 시각, incomplete는 시간 안에 다 찾지 못해 다시 찾을 수 있다는 뜻이다. */
    public record Prices(List<PriceQuote> quotes, Instant checkedAt, boolean incomplete) {
        public Prices { quotes = quotes == null ? List.of() : List.copyOf(quotes); }
        public Prices(List<PriceQuote> quotes, Instant checkedAt) { this(quotes, checkedAt, false); }
    }
    /**
     * 반려동물 동반 여행에서 일정에 들어간 장소(관광·식사·숙소)마다 한국관광공사에서 조회한 동반 정보.
     * info가 null이면 관광공사에서 그 장소를 찾지 못했거나 시간 안에 조회하지 못한 것이다.
     */
    public record PetCheck(Long placeId, String name, PlacePetInfo info) {
        public String status() { return info == null ? "UNKNOWN" : info.status(); }
        /** 화면·AI 안내용 한 줄. 원문의 줄바꿈은 공백으로 바꾼다. */
        public String describe() {
            if (info == null) return "정보 없음 · 관광공사에서 이 장소를 찾지 못해 방문 전 확인이 필요해요";
            if (!info.found()) return "정보 없음 · 관광공사에 등록된 장소지만 반려동물 동반 정보가 없어 방문 전 확인이 필요해요";
            var text = new StringBuilder(info.label());
            for (String part : List.of(info.animals(), info.requirements(), info.etc()))
                if (part != null && !part.isBlank()) text.append(" · ").append(part.replaceAll("\\s*\\n\\s*", " ").strip());
            return text.append(" (한국관광공사)").toString();
        }
    }
    /**
     * 서버가 확인하지 못한 항목 하나에 대한 질문. topic은 PET(반려동물 동반)·PURPOSE(여행 목적 활동)이고,
     * question은 AI 웹 검색에 그대로 보내는 한국어 질문이다.
     */
    public record CheckQuestion(Long placeId, String name, String topic, String question) {}
    /**
     * AI 웹 검색으로 받은 답. verdict는 YES(가능)·NO(불가)·UNKNOWN(못 찾음), status는 VERIFIED(출처 페이지에서 장소 이름을 서버가 확인)·
     * UNVERIFIED(검색 결과에 있는 출처지만 페이지 내용은 확인하지 못함)·NOT_FOUND(근거 없음). AI 답은 확인된 사실로 표시하지 않는다.
     */
    public record CheckAnswer(Long placeId, String name, String topic, String question, String verdict, String answer,
                              String sourceUrl, String status, Instant checkedAt) {
        public boolean found() { return !"NOT_FOUND".equals(status); }
        public String verdictLabel() {
            return switch (verdict == null ? "" : verdict) { case "YES" -> "가능"; case "NO" -> "불가"; default -> "못 찾음"; };
        }
        public String topicLabel() { return "PET".equals(topic) ? "반려동물 동반" : "여행 목적 활동"; }
        public CheckAnswer checkedAt(Instant at) { return new CheckAnswer(placeId, name, topic, question, verdict, answer, sourceUrl, status, at); }
    }
    /** 결과 화면에서 버튼으로(또는 자동 실행 설정으로) 조회한 AI 확인 결과 묶음. checkedAt은 조회 시각이다. */
    public record Verifications(List<CheckAnswer> answers, Instant checkedAt) {
        public Verifications { answers = answers == null ? List.of() : List.copyOf(answers); }
        /** 장소·주제가 같은 답. */
        public CheckAnswer find(Long placeId, String topic) {
            return answers.stream().filter(a -> a.placeId().equals(placeId) && a.topic().equals(topic)).findFirst().orElse(null);
        }
    }
    /**
     * skipped는 일정에 쓰려고 골랐지만 하루 시간 안에 넣지 못해 뺀 관광지다. 필수·유지 장소는 여기에 들어가지 않는다.
     * prices는 완성된 초안에 나중에 붙이는 참고 가격이며 일정의 시각·장소를 바꾸지 않는다.
     * pets는 반려동물 동반 여행에서만 있고(아니면 null), verifications는 AI 웹 검색으로 확인 필요 항목을 찾은 결과다(안 찾았으면 null).
     * 둘 다 일정의 시각·장소를 바꾸지 않으며 이전 작업 JSON에는 없다.
     */
    public record Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby,
                         Lodging lodging, List<PlaceRef> skipped, Prices prices, List<PetCheck> pets, Verifications verifications) {
        public Result { skipped = skipped == null ? List.of() : List.copyOf(skipped); }
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby,
                      Lodging lodging, List<PlaceRef> skipped, Prices prices, List<PetCheck> pets) {
            this(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, null);
        }
        public Result(String destination, List<Day> days, Transport transport, Instant createdAt, Assist assist, Nearby nearby,
                      Lodging lodging, List<PlaceRef> skipped, Prices prices) {
            this(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, null, null);
        }
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
        public Result withAssist(Assist assist) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withDays(List<Day> days) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withNearby(Nearby nearby) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withLodging(Lodging lodging) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withSkipped(List<PlaceRef> skipped) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withPrices(Prices prices) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withPets(List<PetCheck> pets) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
        public Result withVerifications(Verifications verifications) { return new Result(destination, days, transport, createdAt, assist, nearby, lodging, skipped, prices, pets, verifications); }
    }
    /** expiresAt은 이 초안이 사라지는 시각(한국 시간)이다. 부모 추천 작업의 보관 기한을 따른다. */
    public record View(UUID id, String status, String message, boolean pending, Input input, Result result, LocalDateTime expiresAt) {
        public View(UUID id, String status, String message, boolean pending, Input input, Result result) {
            this(id, status, message, pending, input, result, null);
        }
        /** 화면용 보관 기한. 예) "11월 2일 14:30". 저장 JSON에는 들어가지 않는다. */
        public String expiresAtText() {
            return expiresAt == null ? null : java.time.format.DateTimeFormatter.ofPattern("M월 d일 HH:mm").format(expiresAt);
        }
    }
}
