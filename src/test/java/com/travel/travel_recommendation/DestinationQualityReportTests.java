package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;

/**
 * 여행지 추천 품질 측정 보고서. 자연어로 원하는 여행을 말했을 때, 추천된 여행지에서 그 여행을 실제로 할 수 있는지와
 * 추천 이유에 "방문 전 확인이 필요합니다" 같은 말이 얼마나 나오는지를 잰다. 개선 전후를 같은 기준으로 비교하기 위한 도구다.
 * 실제 GPT(조건 분석·목적 검색어·순위)·카카오·관광공사를 호출하므로 RUN_QUALITY_REPORT=true일 때만 실행하고,
 * 테스트 트랜잭션 안에서 돌린 뒤 롤백해 DB에는 남기지 않는다. 보고서는 target/quality/destination-quality-*.md다.
 *
 * '원하는 여행을 할 수 있다'의 기준은 관광공사 새 분류체계(lclsSystm)에서 시나리오마다 정한 소분류에 등록된 장소가
 * 그 여행지(시·군, 광역시·제주는 시·도 전체)에 한 곳 이상 있는지다(예: 등산 → 산·국립공원·도립공원·군립공원).
 */
@EnabledIfEnvironmentVariable(named="RUN_QUALITY_REPORT",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class DestinationQualityReportTests {
    @Autowired TravelConditionOptions options;
    @Autowired TravelConditionService drafts;
    @Autowired ConditionReviewService reviews;
    @Autowired RecommendationJobService recommendations;
    @Autowired PurposePlaceService purposes;
    @Autowired DestinationRecommendationService ranking;
    @Autowired TourApiClient tour;

    /** 측정 시나리오. categories는 그 여행을 할 수 있는 곳으로 보는 관광공사 소분류 코드다. */
    record Scenario(String name, String text, List<String> categories) {}
    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("등산", "11월 14일부터 1박 2일로 둘이서 산에 가서 등산하고 싶어요. 자동차로 가요.",
            List.of("NA010100", "NA040100", "NA040200", "NA040300")),
        new Scenario("단풍 숲길", "가을 단풍 보면서 숲길을 천천히 걷고 싶어요. 11월 7일부터 1박 2일이요.",
            List.of("NA040100", "NA010200", "NA040600", "NA040700")),
        new Scenario("계곡 물놀이", "여름에 아이들이랑 계곡에서 물놀이하고 싶어요. 2박 3일 가족여행이에요.",
            List.of("NA010400")),
        new Scenario("혼자 바다", "혼자 바다 보러 갈 만한 데 추천해줘.",
            List.of("NA020900", "NA020800")),
        new Scenario("아이 체험", "초등학생 아이 둘이랑 1박 2일로 체험하면서 배울 수 있는 여행 가고 싶어요.",
            List.of("EX030100", "EX030200", "EX030300", "VE070500", "VE020100", "VE020300", "VE020400")),
        new Scenario("역사 유적", "역사 유적지를 둘러보는 여행을 하고 싶어요. 2박 3일이요.",
            List.of("HS010100", "HS010200", "HS010600", "HS010700", "HS010800")),
        new Scenario("상어", "상어 보고 싶어.", List.of("VE020400")),
        new Scenario("섬", "섬에 가서 조용히 쉬고 싶어요. 1박 2일이요.", List.of("NA020500")));
    /** 추천 이유에서 원하는 여행을 확인하지 못했다고 미루는 표현. */
    static final Pattern HEDGE = Pattern.compile("확인이 필요|확인하지 못|확인되지 않|확인이 어려|알 수 없|찾지 못|확실하지 않|보장할 수 없");

    record Candidate(int rank, String name, String region, int evidence, List<String> evidenceNames, String reason, int hedges) {}
    record Measured(Scenario scenario, String interpreted, List<Candidate> candidates, String error) {}

    /** 시·도 코드와 그 안의 시·군·구 코드들(광역시·제주처럼 시·도 전체면 빈 목록). */
    record RegionCodes(String province, List<String> districts) {}
    private final Map<String, RegionCodes> regionCodes = new HashMap<>();
    private final Map<String, TourApiClient.CategoryPlaces> counted = new HashMap<>();

    @Test void writesDestinationQualityReport() throws Exception {
        List<Measured> measured = new ArrayList<>();
        for (var scenario : SCENARIOS) {
            try { measured.add(measure(scenario)); }
            catch (Exception error) { measured.add(new Measured(scenario, "", List.of(), error.toString())); }
        }
        var file = writeReport(measured);
        System.out.println("여행지 추천 품질 보고서: " + file.toAbsolutePath());
        assertThat(measured).anySatisfy(m -> assertThat(m.error()).isNull());
    }

    /** 홈 화면의 "이 문장으로 여행지 추천받기"와 같은 순서(조건 분석 → 추천 작업 → 목적 시설 → 후보 → 순위)로 추천을 만든다. */
    private Measured measure(Scenario s) throws Exception {
        var choices = options.getChoices();
        var form = new TravelConditionForm();
        form.setInputMode("TEXT"); form.setOriginalText(s.text());
        var source = drafts.createDraft(form, choices);
        var analysis = reviews.analyze(source, choices);
        var conditions = reviews.confirm(analysis.form(), source, analysis.summary(), choices).forDirectRecommendation();
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        UUID id = recommendations.create(conditions, null, hash);
        try { purposes.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 목적 시설 없이 계속한다. */ }
        recommendations.prepare(id);
        var work = ranking.claim(id);
        if (work == null) throw new IllegalStateException("추천 작업을 시작하지 못했어요: " + recommendations.view(id, null, hash).status());
        ranking.complete(work, ranking.generate(work));
        String interpreted = "지역 " + (blank(conditions.region()) ? "없음" : conditions.region())
            + " · 취향 " + (conditions.themeNames().isEmpty() ? "없음" : String.join("·", conditions.themeNames()))
            + " · 목적 " + (blank(conditions.travelPurpose()) ? "없음" : conditions.travelPurpose())
            + (conditions.purposeSearch() == null ? "" : " · 목적 검색어 " + String.join("·", conditions.purposeSearch().keywords()));
        List<Candidate> candidates = new ArrayList<>();
        for (var ranked : recommendations.view(id, null, hash).recommendations()) {
            var d = ranked.destination();
            var evidence = evidence(d.region(), s.categories());
            candidates.add(new Candidate(ranked.rank(), d.name(), d.region(), evidence.count(), evidence.names(), ranked.reason(),
                (int) HEDGE.matcher(ranked.reason() == null ? "" : ranked.reason()).results().count()));
        }
        return new Measured(s, interpreted, candidates, null);
    }

    /** 여행지 안에서 시나리오 소분류들에 등록된 관광공사 장소 수(합)와 이름 몇 개. 같은 여행지·분류는 한 번만 조회한다. */
    private TourApiClient.CategoryPlaces evidence(String region, List<String> categories) {
        var codes = regionCodes.computeIfAbsent(region, this::codesOf);
        if (codes == null) return new TourApiClient.CategoryPlaces(0, List.of());
        // 시·도 전체면 시·군·구 없이 한 번, 아니면 시·군(일반구가 있으면 구마다)별로 세어 더한다.
        List<String> districts = codes.districts().isEmpty() ? Collections.singletonList(null) : codes.districts();
        int count = 0; List<String> names = new ArrayList<>();
        for (String category : categories) for (String district : districts) {
            var found = counted.computeIfAbsent(region + ":" + district + ":" + category,
                key -> tour.categoryPlaces(codes.province(), district, category));
            count += found.count();
            found.names().stream().limit(2).filter(n -> names.size() < 4).forEach(names::add);
        }
        return new TourApiClient.CategoryPlaces(count, List.copyOf(names));
    }

    /** "강원특별자치도 속초시" → 51·[210], "경기도 수원시" → 41·[111, 113, 115, 117], "부산광역시" → 26·[]. 찾지 못하면 null이다. */
    private RegionCodes codesOf(String region) {
        String[] parts = region.strip().split("\\s+");
        var province = tour.districtCode(null, parts[0]).orElse(null);
        if (province == null) return null;
        if (parts.length < 2) return new RegionCodes(province, List.of());
        var districts = tour.cityDistrictCodes(province, parts[1]);
        return districts.isEmpty() ? null : new RegionCodes(province, districts);
    }

    private Path writeReport(List<Measured> measured) throws Exception {
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        var out = new StringBuilder();
        out.append("# 여행지 추천 품질 측정 (").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(")\n\n")
            .append("자연어 한 문장으로 추천받은 결과다. '근거 있는 후보'는 그 여행의 관광공사 소분류 장소가 여행지에 한 곳 이상 있는 후보다. ")
            .append("'확인 필요 문구'는 추천 이유에 나온 '확인이 필요·확인하지 못·찾지 못' 같은 표현 수다.\n\n")
            .append("| 시나리오 | 근거 있는 후보 | 1순위 근거 | 1~3순위 근거 | 확인 필요 문구(이유 수) | 추천 순위 |\n|---|---|---|---|---|---|\n");
        int hedgedReasons = 0, reasons = 0, withEvidence = 0, candidates = 0;
        for (var m : measured) {
            if (m.error() != null) { out.append("| ").append(m.scenario().name()).append(" | 실패: ").append(m.error().replace("|", "/")).append(" |||||\n"); continue; }
            var c = m.candidates();
            long ok = c.stream().filter(x -> x.evidence() > 0).count();
            long top3 = c.stream().filter(x -> x.rank() <= 3 && x.evidence() > 0).count();
            long hedged = c.stream().filter(x -> x.hedges() > 0).count();
            hedgedReasons += hedged; reasons += c.size(); withEvidence += ok; candidates += c.size();
            out.append("| ").append(m.scenario().name())
                .append(" | ").append(ok).append("/").append(c.size())
                .append(" | ").append(c.stream().filter(x -> x.rank() == 1).findFirst().map(x -> x.evidence() > 0 ? "있음" : "없음").orElse("-"))
                .append(" | ").append(top3).append("/").append(Math.min(3, c.size()))
                .append(" | ").append(c.stream().mapToInt(Candidate::hedges).sum()).append(" (").append(hedged).append("/").append(c.size()).append(")")
                .append(" | ").append(c.stream().map(x -> x.rank() + "." + x.name() + (x.evidence() > 0 ? "" : "✗")).collect(Collectors.joining(" "))).append(" |\n");
        }
        out.append("\n합계: 근거 있는 후보 ").append(withEvidence).append("/").append(candidates)
            .append(", 확인 필요 문구가 있는 이유 ").append(hedgedReasons).append("/").append(reasons).append(". ✗는 근거 없는 후보다.\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            out.append("\n## ").append(m.scenario().name()).append("\n\n")
                .append("- 입력: ").append(m.scenario().text()).append("\n")
                .append("- 해석: ").append(m.interpreted()).append("\n")
                .append("- 근거 분류: ").append(String.join(", ", m.scenario().categories())).append("\n");
            for (var c : m.candidates())
                out.append("- ").append(c.rank()).append(". ").append(c.name()).append(" — 근거 ").append(c.evidence()).append("곳")
                    .append(c.evidenceNames().isEmpty() ? "" : "(" + String.join("·", c.evidenceNames()) + ")")
                    .append(c.hedges() > 0 ? " · 확인 필요 문구 " + c.hedges() : "").append("\n  - 이유: ").append(c.reason()).append("\n");
        }
        var folder = Path.of("target", "quality");
        Files.createDirectories(folder);
        var file = folder.resolve("destination-quality-" + now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
