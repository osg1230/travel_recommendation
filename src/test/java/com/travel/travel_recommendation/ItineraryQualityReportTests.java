package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.Place;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

/**
 * 일정 품질 측정 보고서. 개선 전후를 같은 기준으로 비교하려고 고정 시나리오마다 실제 카카오·관광공사·GPT로 일정을 두 번 만들고
 * 지표를 target/quality/에 Markdown으로 남긴다. 유료 API를 쓰므로 RUN_QUALITY_REPORT=true일 때만 실행한다.
 * 테스트 트랜잭션 안에서 실행하고 끝나면 롤백하므로 DB에는 아무것도 남지 않는다.
 *
 * 지표
 * - 공식 관광지 비율: 일정 관광지 중 관광공사(TourAPI)에 관광지·문화시설로 등록된 곳(이름 검색 + 1km 안)의 비율
 * - 종류 수·같은 종류 최대: 일정 관광지의 종류(TripConstraints.kind, 해수욕장·해변은 하나로 본다)
 * - 후보 최다 종류: 관광지 후보 목록에서 가장 많은 종류와 그 수(예: 해변 8)
 * - 하루 이동·최장 이동: 하루 이동시간 합의 평균과 가장 긴 한 번 이동(분)
 * - 다시 만들기 겹침: 첫 초안의 관광지가 '다른 관광지로 다시 만들기'로 만든 두 번째 초안에도 나온 비율
 *   (2026-10-30 3차 측정까지는 버튼이 없어 같은 조건으로 두 번 만든 초안끼리 비교했다)
 * - 인기 음식점 식사·인기 숙소: 첫 초안의 식사·숙소 중 관광공사 연관 관광지의 인기 음식점·숙소로 확인한 곳의 수(5단계)
 * 배치 지표(2026-10-31 추가, 모두 첫 초안 기준, 좌표는 일정 항목의 지도 링크에서 읽는다)
 * - 체류시간: 낮 관광지 체류시간의 최소~최대(분). 모든 곳이 같으면 한 값이다
 * - 동선(실제/최단): 하루 관광지를 실제 순서로 돈 직선거리 ÷ 같은 곳을 가장 짧게 도는 순서의 직선거리(DayRoute).
 *   숙박하는 날은 숙소에서 끝나고, 다음 날은 숙소에서 시작한다. 1.00이면 가장 짧은 순서다
 * - 식사 우회: 식사 앞뒤 장소 사이를 바로 갈 때보다 식당을 들르느라 더 가는 직선거리(km, 끼니 평균)
 * - 남는 시간: 관광을 마친 시각(숙박하는 날은 숙소 도착)부터 하루 종료 시각까지 남는 시간(분, 하루 평균)
 */
@EnabledIfEnvironmentVariable(named="RUN_QUALITY_REPORT",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false",
    "travel.openai.api-key=${OPENAI_API_KEY:${OPEN_AI_API:${Portfolio_java_API:}}}","travel.kakao.rest-api-key=${KAKAO_REST_API_KEY:}",
    "travel.tourapi.service-key=${TOUR_API_SERVICE_KEY:}","travel.tago.service-key=${TAGO_SERVICE_KEY:${TOUR_API_SERVICE_KEY:}}"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class ItineraryQualityReportTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired PlaceCandidateService candidates;
    @Autowired PlaceDiscoveryService discovery;
    @Autowired ItineraryJobService schedules;
    @Autowired PlacePickService placePicks;
    @Autowired NearbyRestaurantService restaurants;
    @Autowired PlaceRepository places;
    @Autowired AiJobRepository jobs;
    @Autowired TourApiClient tour;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    /** 측정 시나리오. themes는 여행 취향 키워드 이름들이고 없으면 빈 목록이다. */
    record Scenario(String name, String destination, int days, List<String> themes) {}
    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("강릉 1박 2일", "강릉", 2, List.of()),
        new Scenario("강릉 1박 2일·바다", "강릉", 2, List.of("바다")),
        new Scenario("경주 1박 2일·문화·역사", "경주", 2, List.of("문화·역사")),
        new Scenario("부산 2박 3일", "부산", 3, List.of()),
        new Scenario("전주 당일", "전주", 1, List.of()),
        new Scenario("제주 2박 3일·자연", "제주", 3, List.of("자연")),
        // 취향 2개 시나리오(2026-11-05 추가). 취향 비율 맞추기(개선 계획 3단계)가 필요한지 본다.
        new Scenario("부산 2박 3일·바다·문화·역사", "부산", 3, List.of("바다", "문화·역사")));
    /** 출발일은 같은 계절(가을 주말)로 고정해 실행하는 날에 따라 계절 판단이 바뀌지 않게 한다. */
    static final LocalDate START = LocalDate.of(2026, 11, 14);

    /** official은 관광공사 등록 여부이고, 관광공사 조회에 실패하면 null이다. */
    record Visit(long placeId, String name, String kind, Boolean official, Double longitude, Double latitude) {}
    /**
     * aiOrdered는 GPT가 관광지 순서를 정했는지다. false면 GPT 응답 실패 등으로 서버 규칙 순서를 썼다.
     * aiOrder는 관광지 후보를 고른 순서(GPT 순서 또는 서버 규칙 순서), activities는 GPT가 고른 장소별 주된 활동이다.
     */
    record Draft(UUID id, List<Visit> visits, int dailyMoveMinutes, int longestMoveMinutes, boolean aiOrdered,
                 int meals, int popularMeals, int stays, int popularStays, Arrangement arrangement,
                 List<Long> aiOrder, Map<Long, String> activities) {}
    /** 배치 지표. routeRatios·idleMinutes는 날마다, mealDetours는 끼니마다 하나씩이고, days는 보고서에 적을 날짜별 동선이다. */
    record Arrangement(int minVisit, int maxVisit, List<Double> routeRatios, List<Double> mealDetours, List<Integer> idleMinutes, List<String> days) {}
    record Measured(Scenario scenario, int candidateCount, Map<String, Long> candidateKinds, Draft first, Draft second, String error) {}

    private final Map<Long, Boolean> officialCache = new HashMap<>();

    @Test void writesQualityReport() throws Exception {
        // 전에 조회해 둔 카카오 장소가 후보에 섞이지 않게 만료 처리하고 이번 실행에서 새로 찾은 장소만 쓴다(롤백되므로 실제 DB는 그대로).
        ExternalPlaceCache.expireExisting(jdbc);
        List<Measured> measured = new ArrayList<>();
        for (var scenario : SCENARIOS) {
            // 한 시나리오가 실패해도 나머지는 계속 재고, 실패 이유를 보고서에 남긴다.
            try { measured.add(measure(scenario)); }
            catch (Exception error) { measured.add(new Measured(scenario, 0, Map.of(), null, null, error.toString())); }
        }
        var file = writeReport(measured);
        System.out.println("일정 품질 보고서: " + file.toAbsolutePath());
        assertThat(measured).anySatisfy(m -> assertThat(m.error()).isNull());
    }

    private Measured measure(Scenario s) throws Exception {
        String token = UUID.randomUUID().toString();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
        var keywords = s.themes().stream().map(this::theme).toList();
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", s.destination(), START, START.plusDays(s.days() - 1),
            s.days(), null, 2, s.days() > 1, false, keywords, "", false, null);
        // 여행지 추천(GPT 순위)은 측정 대상이 아니라서 건너뛰고, 시나리오의 여행지를 추천 결과로 바로 둔다.
        UUID parent = recommendations.create(conditions, null, hash);
        recommendations.prepare(parent);
        var destination = recommendations.view(parent, null, hash).candidates().stream()
            .filter(c -> c.name().equals(s.destination())).findFirst().orElseThrow();
        jobs.findById(parent).orElseThrow().completeRecommendation(
            mapper.writeValueAsString(List.of(new RankedDestination(1, destination, "품질 측정"))));
        em.flush();
        discovery.refresh(parent, destination.placeId(), null, hash);
        em.flush();
        var pool = candidates.find(parent, destination.placeId(), null, hash).attractions();
        var kinds = pool.stream().collect(Collectors.groupingBy(TripConstraints::kind, LinkedHashMap::new, Collectors.counting()));
        // 두 번째 초안은 첫 초안 화면의 '다른 관광지로 다시 만들기'(2026-10-30 4단계)로 만든다.
        var first = draft(parent, destination.placeId(), hash, null);
        return new Measured(s, pool.size(), kinds, first, draft(parent, destination.placeId(), hash, first.id()), null);
    }

    /**
     * 빠른 일정 만들기와 같은 기본값(09:00~18:00, 체류 90분)으로 초안 하나를 만들고 관광지·이동시간을 읽는다.
     * previous가 있으면 그 초안에서 '다른 관광지로 다시 만들기'를 누른 것과 같다.
     */
    private Draft draft(UUID parent, long destination, byte[] hash, UUID previous) {
        var options = schedules.options(parent, destination, null, hash);
        UUID id = previous == null
            ? schedules.create(parent, destination, null, hash, options.start(), options.end(), options.transport(), options.visitMinutes())
            : schedules.recreate(previous, null, hash);
        // 실제 작업 순서(RecommendationJobWorker)와 같게: AI 관광지 순서 → 주변 음식점 → 일정 생성. 앞의 두 단계는 실패해도 서버 규칙으로 계속한다.
        try { placePicks.enrich(id); } catch (RuntimeException skipped) { /* 서버 규칙으로 고른 관광지를 그대로 쓴다. */ }
        try { restaurants.enrich(id); } catch (RuntimeException skipped) { /* 기존 음식점 후보를 쓴다. */ }
        var input = schedules.view(id, null, hash).input();
        var picking = input.picking();
        boolean aiOrdered = picking != null && picking.byAi();
        var work = schedules.claim(id);
        if (work == null) throw new IllegalStateException("일정 작업을 시작하지 못했어요.");
        var result = schedules.generate(work);
        schedules.complete(work, result);
        em.flush();
        List<Visit> visits = new ArrayList<>();
        int moveSeconds = 0, longestSeconds = 0, meals = 0, popularMeals = 0;
        Set<Long> stays = new HashSet<>(), popularStays = new HashSet<>();
        for (var day : result.days()) for (var item : day.items()) {
            if (item.travelSeconds() != null) {
                moveSeconds += item.travelSeconds();
                longestSeconds = Math.max(longestSeconds, item.travelSeconds());
            }
            // 식사·숙소는 관광공사 연관 관광지의 인기 음식점·숙소로 확인한 곳인지 센다(5단계).
            if (("MEAL".equals(item.type()) || "STAY".equals(item.type())) && item.placeId() != null) {
                boolean popular = places.findById(item.placeId()).map(p -> PlaceRankDetails.popular(p, mapper)).orElse(false);
                if ("MEAL".equals(item.type())) { meals++; if (popular) popularMeals++; }
                else { stays.add(item.placeId()); if (popular) popularStays.add(item.placeId()); }
            }
            if (!"VISIT".equals(item.type()) || item.placeId() == null) continue;
            var place = places.findById(item.placeId()).orElseThrow();
            visits.add(new Visit(place.getId(), place.getName(), TripConstraints.kind(candidates.candidate(place)), official(place),
                place.getLongitude() == null ? null : place.getLongitude().doubleValue(),
                place.getLatitude() == null ? null : place.getLatitude().doubleValue()));
        }
        return new Draft(id, visits, moveSeconds / 60 / result.days().size(), longestSeconds / 60, aiOrdered,
            meals, popularMeals, stays.size(), popularStays.size(), arrangement(input, result),
            // 고른 순서: 일정에 넣으려고 고른 관광지(AI 순서로 줄 세움) 다음에 예비 관광지를 잇는다.
            java.util.stream.Stream.concat(input.places().attractions().stream(), input.reserve().stream()).map(PlaceCandidate::placeId).toList(),
            picking == null ? Map.of() : picking.activities());
    }

    /**
     * AI 상위 후보 반영률(2026-11-05 추가, 개선 계획 0단계). 일정에 넣은 관광지 수가 n이면, 고른 순서의 앞 n곳 가운데 일정에 들어간 비율이다.
     * 1.0이면 AI가 앞에 둔 곳을 모두 썼다는 뜻이고, 낮으면 거리 위주 배치로 뒤쪽 후보가 대신 들어갔다는 뜻이다.
     */
    static double aiTopShare(List<Long> order, Collection<Long> visited) {
        var used = new HashSet<>(visited);
        int n = Math.min(used.size(), order.size());
        if (n == 0) return 0;
        return order.subList(0, n).stream().filter(used::contains).count() / (double) n;
    }

    /** 여행 취향과 GPT가 고른 주된 활동의 대응. 일정 생성과 같은 표를 쓴다. 활동 정보가 없는 장소는 어느 취향에도 세지 않는다. */
    static final Map<String, Set<String>> THEME_ACTIVITIES = OpenAiPlacePickClient.THEME_ACTIVITIES;

    /**
     * 취향 비율 일치도(2026-11-05 추가). 고른 취향마다 그 취향에 맞는 활동의 관광지 수를 세고, 취향끼리 같은 비율(1/k)과의 차이로 점수를 낸다.
     * 1 - (각 취향 비율과 1/k의 차이 합) / 2 이며, 1.0이면 취향끼리 고르게 담겼다는 뜻이다. 취향이 하나면 그 취향에 맞는 관광지 비율이다.
     * 취향이 없거나 맞는 관광지가 하나도 없으면 null이다.
     */
    static Double themeBalance(List<String> themes, Map<Long, String> activities, List<Long> visited) {
        if (themes.isEmpty() || visited.isEmpty()) return null;
        if (themes.size() == 1) {
            var wanted = THEME_ACTIVITIES.getOrDefault(themes.getFirst(), Set.of());
            return visited.stream().filter(id -> wanted.contains(activities.get(id))).count() / (double) visited.size();
        }
        List<Long> counts = themes.stream().map(t -> visited.stream()
            .filter(id -> THEME_ACTIVITIES.getOrDefault(t, Set.of()).contains(activities.get(id))).count()).toList();
        long total = counts.stream().mapToLong(Long::longValue).sum();
        if (total == 0) return null;
        double gap = counts.stream().mapToDouble(c -> Math.abs(c / (double) total - 1.0 / themes.size())).sum();
        return 1 - gap / 2;
    }

    /** 배치 지표를 잰다. 저녁 관광(숙소 도착 뒤)은 낮 동선·체류시간에서 뺀다. */
    static Arrangement arrangement(ItineraryPlan.Input input, ItineraryPlan.Result result) {
        int minVisit = Integer.MAX_VALUE, maxVisit = 0;
        List<Double> ratios = new ArrayList<>(), detours = new ArrayList<>();
        List<Integer> idle = new ArrayList<>();
        List<String> days = new ArrayList<>();
        double[] hotel = null;
        for (var day : result.days()) {
            var items = day.items();
            int stayAt = -1;
            for (int i = 0; i < items.size() && stayAt < 0; i++) if ("STAY".equals(items.get(i).type())) stayAt = i;
            List<double[]> stops = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int i = 0; i < (stayAt < 0 ? items.size() : stayAt); i++) {
                var item = items.get(i);
                if (!"VISIT".equals(item.type())) continue;
                stops.add(point(item)); names.add(item.name());
                int minutes = (int) Duration.between(item.start(), item.end()).toMinutes();
                minVisit = Math.min(minVisit, minutes); maxVisit = Math.max(maxVisit, minutes);
            }
            double[] end = stayAt < 0 ? null : point(items.get(stayAt));
            double actual = DayRoute.length(stops, hotel, end, ItineraryQualityReportTests::km);
            double best = DayRoute.length(DayRoute.shortest(stops, hotel, end, ItineraryQualityReportTests::km), hotel, end, ItineraryQualityReportTests::km);
            ratios.add(best <= 0 ? 1.0 : actual / best);
            days.add(day.number() + "일차: " + String.join(" → ", names) + String.format(" (직선 %.1fkm, 가장 짧은 순서 %.1fkm)", actual, best));
            for (int i = 0; i < items.size(); i++) {
                if (!"MEAL".equals(items.get(i).type())) continue;
                var before = neighbor(items, i, -1);
                var after = neighbor(items, i, 1);
                if (before == null || after == null) continue;
                var meal = point(items.get(i));
                detours.add(km(before, meal) + km(meal, after) - km(before, after));
            }
            // 숙박하는 날은 숙소에 도착한 시각, 아니면 마지막 장소를 떠나는 시각(귀가 이동 제외)부터 하루 종료까지 남는 시간이다.
            LocalTime finished = null;
            if (stayAt >= 0) finished = items.get(stayAt).start();
            else for (var item : items) if (!"TRANSIT".equals(item.type())) finished = item.end();
            if (finished != null) idle.add((int) Math.max(0, Duration.between(finished, input.dayEnd(day.number())).toMinutes()));
            if (stayAt >= 0) hotel = end;
        }
        return new Arrangement(minVisit == Integer.MAX_VALUE ? 0 : minVisit, maxVisit, ratios, detours, idle, days);
    }

    /** 식사 앞(direction=-1)이나 뒤(1)의 장소 좌표. 이동 항목과 같은 식당의 자유시간은 건너뛰고, 집 이동(장소 없음)이나 하루 끝이면 null이다. */
    private static double[] neighbor(List<ItineraryPlan.Item> items, int at, int direction) {
        var meal = items.get(at);
        for (int i = at + direction; i >= 0 && i < items.size(); i += direction) {
            var item = items.get(i);
            if (item.mapUrl() == null) return null;
            if ("TRANSIT".equals(item.type()) || Objects.equals(item.placeId(), meal.placeId())) continue;
            return point(item);
        }
        return null;
    }

    /** 일정 항목 지도 링크(https://map.kakao.com/link/map/이름,위도,경도)의 {경도, 위도}. 이름은 URL 인코딩돼 쉼표가 없다. */
    private static double[] point(ItineraryPlan.Item item) {
        var parts = item.mapUrl().split(",");
        return new double[]{Double.parseDouble(parts[parts.length - 1]), Double.parseDouble(parts[parts.length - 2])};
    }

    private static double km(double[] a, double[] b) { return meters(a[0], a[1], b[0], b[1]) / 1000; }

    /** 일정 관광지 중 서로 가장 먼 두 곳의 직선거리(km). 작으면 한 동네에 몰린 일정이다. */
    private static String spreadKm(List<Visit> visits) {
        double max = 0;
        for (var a : visits) for (var b : visits) {
            if (a.longitude() == null || b.longitude() == null) continue;
            max = Math.max(max, meters(a.longitude(), a.latitude(), b.longitude(), b.latitude()));
        }
        return String.format("%.1f", max / 1000);
    }

    private TravelKeyword theme(String name) {
        return jdbc.queryForObject("""
            SELECT k.keyword_id, k.keyword_name, k.value_json FROM dbo.keywords k
            JOIN dbo.keyword_categories c ON c.category_id = k.category_id
            WHERE c.category_code = 'THEME' AND k.keyword_name = ?""",
            (rs, i) -> new TravelKeyword(rs.getLong(1), "THEME", rs.getString(2), rs.getString(3)), name);
    }

    /**
     * 관광공사에 관광지·문화시설로 등록된 곳인지 확인한다. 이름으로 찾은 결과 중 1km 안에 있으면 같은 곳으로 본다.
     * 출처마다 띄어쓰기·지역명이 달라(예: "강릉선교장"·"강릉 선교장") 공백을 뺀 이름과 첫 단어로도 찾는다.
     */
    private Boolean official(Place place) {
        if (officialCache.containsKey(place.getId())) return officialCache.get(place.getId());
        Boolean found = false;
        if (place.getLongitude() == null || place.getLatitude() == null) found = null;
        else {
            double x = place.getLongitude().doubleValue(), y = place.getLatitude().doubleValue();
            String name = place.getName().strip();
            var words = new LinkedHashSet<>(List.of(name, name.replace(" ", ""), name.split("\\s+")[0]));
            try {
                for (String word : words) {
                    if (word.length() < 2 || word.length() > 40) continue;
                    if (tour.searchFacilities(word).stream().anyMatch(t -> meters(x, y, t.longitude(), t.latitude()) <= 1000)) { found = true; break; }
                }
            } catch (RecommendationException error) { found = null; }
        }
        officialCache.put(place.getId(), found);
        return found;
    }

    // 짧은 거리라 평면으로 보고 계산한다(PlaceDiscoveryService와 같은 방식).
    private static double meters(double lon1, double lat1, double lon2, double lat2) {
        double x = (lon2 - lon1) * Math.cos(Math.toRadians((lat1 + lat2) / 2)), y = lat2 - lat1;
        return Math.sqrt(x * x + y * y) * 111_320;
    }

    private Path writeReport(List<Measured> measured) throws Exception {
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        var out = new StringBuilder();
        out.append("# 일정 품질 측정 (").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(")\n\n")
            .append("조건: 출발 ").append(START).append("(가을 주말), 2명, 자동차, 09:00~18:00, 기본 체류 90분(빠른 일정 기본값). ")
            .append("시나리오마다 초안을 만들고 그 초안에서 '다른 관광지로 다시 만들기'로 한 번 더 만들었다. 표의 값은 첫 초안 기준이다.\n\n")
            .append("| 시나리오 | 후보 관광지 | 후보 최다 종류 | 일정 관광지 | 공식 관광지 | 종류 수 | 같은 종류 최대 | 관광지 분포(km) | 하루 이동(분) | 최장 이동(분) | 다시 만들기 겹침 | AI 순서 | 인기 음식점 식사 | 인기 숙소 |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var m : measured) {
            if (m.error() != null) { out.append("| ").append(m.scenario().name()).append(" | 실패: ").append(m.error().replace("|", "/")).append(" |||||||||||||\n"); continue; }
            var visits = m.first().visits();
            var kindCounts = visits.stream().collect(Collectors.groupingBy(Visit::kind, Collectors.counting()));
            out.append("| ").append(m.scenario().name())
                .append(" | ").append(m.candidateCount())
                .append(" | ").append(topKind(m.candidateKinds()))
                .append(" | ").append(visits.size())
                .append(" | ").append(officialText(visits))
                .append(" | ").append(kindCounts.size())
                .append(" | ").append(kindCounts.values().stream().mapToLong(Long::longValue).max().orElse(0))
                .append(" | ").append(spreadKm(visits))
                .append(" | ").append(m.first().dailyMoveMinutes())
                .append(" | ").append(m.first().longestMoveMinutes())
                .append(" | ").append(overlap(m.first(), m.second()))
                .append(" | ").append(m.first().aiOrdered() ? "성공" : "실패").append("/").append(m.second().aiOrdered() ? "성공" : "실패")
                .append(" | ").append(m.first().popularMeals()).append("/").append(m.first().meals())
                .append(" | ").append(m.first().stays() == 0 ? "-" : m.first().popularStays() + "/" + m.first().stays()).append(" |\n");
        }
        // 개선 계획(2026-11-03) 0단계에서 더한 지표. 다음 장소 고르기를 바꾸면 올라야 하는 값이다.
        out.append("\n### 가치 지표 (첫 초안·두 번째 초안)\n\n")
            .append("| 시나리오 | 취향 | AI 상위 후보 반영률 | 취향 비율 일치도 | 활동 분포 |\n")
            .append("|---|---|---|---|---|\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            out.append("| ").append(m.scenario().name())
                .append(" | ").append(m.scenario().themes().isEmpty() ? "-" : String.join("·", m.scenario().themes()))
                .append(" | ").append(percent(aiTopShare(m.first().aiOrder(), ids(m.first())))).append(" / ").append(percent(aiTopShare(m.second().aiOrder(), ids(m.second()))))
                .append(" | ").append(percent(themeBalance(m.scenario().themes(), m.first().activities(), ids(m.first()))))
                .append(" / ").append(percent(themeBalance(m.scenario().themes(), m.second().activities(), ids(m.second()))))
                .append(" | ").append(activityText(m.first())).append(" |\n");
        }
        out.append("\nAI 상위 후보 반영률은 일정 관광지 수가 n일 때 고른 순서(AI 순서로 줄 세운 선택 + 예비)의 앞 n곳 중 일정에 들어간 비율이다. ")
            .append("취향 비율 일치도는 취향이 하나면 그 취향에 맞는 활동(GPT가 고른 주된 활동 기준)의 관광지 비율, ")
            .append("둘 이상이면 취향별 관광지 수가 고른지(1.0이면 같은 비율)다. 활동 분포는 첫 초안 관광지의 GPT 활동이다.\n");
        out.append("\n공식 관광지는 관광공사 이름 검색 결과가 1km 안에 있는지로 판단해, 이름이 크게 다르면 등록된 곳도 빠질 수 있다(개선 전후 같은 기준). ")
            .append("괄호 안 '확인 못 함'은 관광공사 조회 실패 수다. 관광지 분포는 일정 관광지 중 서로 가장 먼 두 곳의 직선거리다. ")
            .append("AI 순서는 첫/두 번째 초안에서 GPT가 관광지 순서를 정했는지이며, 실패하면 서비스처럼 서버 규칙 순서로 만들었다. ")
            .append("인기 음식점 식사·인기 숙소는 첫 초안의 식사·숙소 중 관광공사 연관 관광지의 인기 음식점·숙소로 확인한 곳의 수다.\n");
        out.append("\n### 배치 지표 (첫 초안)\n\n")
            .append("| 시나리오 | 체류시간(분) | 동선 실제/최단 (평균·최대) | 식사 우회(km, 끼니 평균) | 남는 시간(분, 하루 평균) |\n")
            .append("|---|---|---|---|---|\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            var a = m.first().arrangement();
            out.append("| ").append(m.scenario().name())
                .append(" | ").append(a.minVisit() == a.maxVisit() ? String.valueOf(a.minVisit()) : a.minVisit() + "~" + a.maxVisit())
                .append(" | ").append(String.format("%.2f · %.2f", average(a.routeRatios()), a.routeRatios().stream().mapToDouble(Double::doubleValue).max().orElse(1)))
                .append(" | ").append(a.mealDetours().isEmpty() ? "-" : String.format("%.1f", average(a.mealDetours())))
                .append(" | ").append(Math.round(a.idleMinutes().stream().mapToInt(Integer::intValue).average().orElse(0))).append(" |\n");
        }
        out.append("\n동선은 직선거리 기준이라 실제 도로 거리와 다를 수 있다. 1.00이면 그날 관광지를 가장 짧게 도는 순서다. ")
            .append("식사 우회는 식사 앞뒤에 장소가 있는 끼니만 센다(첫 일정 전·숙소로 가기 전 끼니 포함).\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            out.append("\n## ").append(m.scenario().name()).append("\n\n")
                .append("- 후보 종류 분포: ").append(m.candidateKinds().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n")
                .append("- 첫 초안: ").append(visitText(m.first())).append("\n")
                .append("- 두 번째 초안: ").append(visitText(m.second())).append("\n")
                .append("- 첫 초안 동선: ").append(String.join(" / ", m.first().arrangement().days())).append("\n");
        }
        var folder = Path.of("target", "quality");
        Files.createDirectories(folder);
        var file = folder.resolve("itinerary-quality-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static List<Long> ids(Draft draft) { return draft.visits().stream().map(Visit::placeId).toList(); }

    private static String percent(Double value) { return value == null ? "-" : Math.round(value * 100) + "%"; }

    private static String activityText(Draft draft) {
        var counts = draft.visits().stream().collect(Collectors.groupingBy(v -> draft.activities().getOrDefault(v.placeId(), "정보 없음"),
            TreeMap::new, Collectors.counting()));
        return counts.entrySet().stream().map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "));
    }

    private static double average(List<Double> values) {
        return values.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static String topKind(Map<String, Long> kinds) {
        return kinds.entrySet().stream().max(Map.Entry.comparingByValue()).map(e -> e.getKey() + " " + e.getValue()).orElse("-");
    }

    private static String officialText(List<Visit> visits) {
        long yes = visits.stream().filter(v -> Boolean.TRUE.equals(v.official())).count();
        long unknown = visits.stream().filter(v -> v.official() == null).count();
        String ratio = visits.isEmpty() ? "-" : yes + "/" + visits.size() + " (" + Math.round(yes * 100.0 / visits.size()) + "%)";
        return unknown == 0 ? ratio : ratio + " (확인 못 함 " + unknown + ")";
    }

    private static String overlap(Draft first, Draft second) {
        var a = first.visits().stream().map(Visit::name).collect(Collectors.toSet());
        if (a.isEmpty()) return "-";
        long same = second.visits().stream().map(Visit::name).filter(a::contains).count();
        return Math.round(same * 100.0 / a.size()) + "%";
    }

    private static String visitText(Draft draft) {
        return draft.visits().stream().map(v -> v.name() + "(" + v.kind() + (Boolean.TRUE.equals(v.official()) ? ", 공식" : "") + ")")
            .collect(Collectors.joining(" · "));
    }
}
