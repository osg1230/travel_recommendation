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
 */
@EnabledIfEnvironmentVariable(named="RUN_QUALITY_REPORT",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
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

    /** 측정 시나리오. theme은 여행 취향 키워드 이름이고 없으면 null이다. */
    record Scenario(String name, String destination, int days, String theme) {}
    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("강릉 1박 2일", "강릉", 2, null),
        new Scenario("강릉 1박 2일·바다", "강릉", 2, "바다"),
        new Scenario("경주 1박 2일·문화·역사", "경주", 2, "문화·역사"),
        new Scenario("부산 2박 3일", "부산", 3, null),
        new Scenario("전주 당일", "전주", 1, null),
        new Scenario("제주 2박 3일·자연", "제주", 3, "자연"));
    /** 출발일은 같은 계절(가을 주말)로 고정해 실행하는 날에 따라 계절 판단이 바뀌지 않게 한다. */
    static final LocalDate START = LocalDate.of(2026, 11, 14);

    /** official은 관광공사 등록 여부이고, 관광공사 조회에 실패하면 null이다. */
    record Visit(String name, String kind, Boolean official, Double longitude, Double latitude) {}
    /** aiOrdered는 GPT가 관광지 순서를 정했는지다. false면 GPT 응답 실패 등으로 서버 규칙 순서를 썼다. */
    record Draft(UUID id, List<Visit> visits, int dailyMoveMinutes, int longestMoveMinutes, boolean aiOrdered) {}
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
        var keywords = s.theme() == null ? List.<TravelKeyword>of() : List.of(theme(s.theme()));
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
        var picking = schedules.view(id, null, hash).input().picking();
        boolean aiOrdered = picking != null && picking.byAi();
        var work = schedules.claim(id);
        if (work == null) throw new IllegalStateException("일정 작업을 시작하지 못했어요.");
        var result = schedules.generate(work);
        schedules.complete(work, result);
        em.flush();
        List<Visit> visits = new ArrayList<>();
        int moveSeconds = 0, longestSeconds = 0;
        for (var day : result.days()) for (var item : day.items()) {
            if (item.travelSeconds() != null) {
                moveSeconds += item.travelSeconds();
                longestSeconds = Math.max(longestSeconds, item.travelSeconds());
            }
            if (!"VISIT".equals(item.type()) || item.placeId() == null) continue;
            var place = places.findById(item.placeId()).orElseThrow();
            visits.add(new Visit(place.getName(), TripConstraints.kind(candidates.candidate(place)), official(place),
                place.getLongitude() == null ? null : place.getLongitude().doubleValue(),
                place.getLatitude() == null ? null : place.getLatitude().doubleValue()));
        }
        return new Draft(id, visits, moveSeconds / 60 / result.days().size(), longestSeconds / 60, aiOrdered);
    }

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
            .append("조건: 출발 ").append(START).append("(가을 주말), 2명, 자동차, 09:00~18:00, 체류 90분(빠른 일정 기본값). ")
            .append("시나리오마다 초안을 만들고 그 초안에서 '다른 관광지로 다시 만들기'로 한 번 더 만들었다. 표의 값은 첫 초안 기준이다.\n\n")
            .append("| 시나리오 | 후보 관광지 | 후보 최다 종류 | 일정 관광지 | 공식 관광지 | 종류 수 | 같은 종류 최대 | 관광지 분포(km) | 하루 이동(분) | 최장 이동(분) | 다시 만들기 겹침 | AI 순서 |\n")
            .append("|---|---|---|---|---|---|---|---|---|---|---|---|\n");
        for (var m : measured) {
            if (m.error() != null) { out.append("| ").append(m.scenario().name()).append(" | 실패: ").append(m.error().replace("|", "/")).append(" |||||||||||\n"); continue; }
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
                .append(" | ").append(m.first().aiOrdered() ? "성공" : "실패").append("/").append(m.second().aiOrdered() ? "성공" : "실패").append(" |\n");
        }
        out.append("\n공식 관광지는 관광공사 이름 검색 결과가 1km 안에 있는지로 판단해, 이름이 크게 다르면 등록된 곳도 빠질 수 있다(개선 전후 같은 기준). ")
            .append("괄호 안 '확인 못 함'은 관광공사 조회 실패 수다. 관광지 분포는 일정 관광지 중 서로 가장 먼 두 곳의 직선거리다. ")
            .append("AI 순서는 첫/두 번째 초안에서 GPT가 관광지 순서를 정했는지이며, 실패하면 서비스처럼 서버 규칙 순서로 만들었다.\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            out.append("\n## ").append(m.scenario().name()).append("\n\n")
                .append("- 후보 종류 분포: ").append(m.candidateKinds().entrySet().stream().sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> e.getKey() + " " + e.getValue()).collect(Collectors.joining(", "))).append("\n")
                .append("- 첫 초안: ").append(visitText(m.first())).append("\n")
                .append("- 두 번째 초안: ").append(visitText(m.second())).append("\n");
        }
        var folder = Path.of("target", "quality");
        Files.createDirectories(folder);
        var file = folder.resolve("itinerary-quality-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
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
