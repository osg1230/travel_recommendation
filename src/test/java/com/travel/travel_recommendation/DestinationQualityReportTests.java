package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import static org.assertj.core.api.Assertions.*;

/**
 * 여행지 추천 품질 측정 보고서. 자연어로 원하는 여행을 말했을 때, 추천된 여행지에서 그 여행을 실제로 할 수 있는지와
 * 추천 이유에 "방문 전 확인이 필요합니다" 같은 말이 얼마나 나오는지를 잰다. 개선 전후를 같은 기준으로 비교하기 위한 도구다.
 * 실제 GPT(조건 분석·목적 검색어·순위)·카카오·관광공사를 호출하므로 RUN_QUALITY_REPORT=true일 때만 실행한다.
 * 추천 작업은 테스트 트랜잭션 안에서 돌린 뒤 롤백한다. 다만 여행지별 "할 수 있는 것" 목록은 서비스가 30일 보관하는 자료라
 * 트랜잭션 밖에서 실제로 저장해, 다음 측정과 앱에서 관광공사를 다시 부르지 않게 한다. 보고서는 target/quality/destination-quality-*.md다.
 *
 * '원하는 여행을 할 수 있다'의 근거는 여행지 목록({@link DestinationProfile})에서 시나리오마다 정한 관광공사 소분류의 장소 수다
 * (예: 등산 → 산·국립공원·도립공원·군립공원). 여행지 전체를 이 수로 줄 세워 '근거 상위 5곳'과 추천 5곳이 얼마나 겹치는지도 본다.
 */
@EnabledIfEnvironmentVariable(named="RUN_QUALITY_REPORT",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false",
    "travel.openai.api-key=${OPENAI_API_KEY:${OPEN_AI_API:${Portfolio_java_API:}}}","travel.kakao.rest-api-key=${KAKAO_REST_API_KEY:}",
    "travel.tourapi.service-key=${TOUR_API_SERVICE_KEY:}","travel.tago.service-key=${TAGO_SERVICE_KEY:${TOUR_API_SERVICE_KEY:}}"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class DestinationQualityReportTests {
    @Autowired TravelConditionOptions options;
    @Autowired TravelConditionService drafts;
    @Autowired ConditionReviewService reviews;
    @Autowired RecommendationJobService recommendations;
    @Autowired PurposePlaceService purposes;
    @Autowired RegionSuggestionService regions;
    @Autowired DestinationPickService picks;
    @Autowired AiJobRepository jobs;
    @Autowired tools.jackson.databind.ObjectMapper mapper;
    @Autowired DestinationRecommendationService ranking;
    @Autowired DestinationProfileService profiles;
    @Autowired PlaceRepository places;
    @Autowired PlatformTransactionManager transactions;

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

    /** evidenceRank는 여행지 전체를 근거 수로 줄 세웠을 때의 순위(같은 수는 같은 순위)다. */
    record Candidate(int rank, String name, int evidence, int evidenceRank, List<String> evidenceNames, String reason, int hedges) {}
    record Measured(Scenario scenario, String interpreted, List<Candidate> candidates, List<String> evidenceTop, String error) {}

    /** 여행지 이름 → "할 수 있는 것" 목록. */
    private final Map<String, Map<String, DestinationProfile.Category>> profileByName = new LinkedHashMap<>();

    @Test void writesDestinationQualityReport() throws Exception {
        // 서비스가 30일 보관하는 여행지 목록은 테스트 롤백과 상관없이 저장한다(이미 있으면 관광공사를 부르지 않는다).
        var outside = new TransactionTemplate(transactions);
        outside.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        outside.executeWithoutResult(status -> profiles.prepareMissing());
        for (var destination : places.findDestinations()) profileByName.put(destination.getName(), profiles.profile(destination.getId()));
        List<Measured> measured = new ArrayList<>();
        for (var scenario : SCENARIOS) {
            try { measured.add(measure(scenario)); }
            catch (Exception error) { measured.add(new Measured(scenario, "", List.of(), List.of(), error.toString())); }
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
        // 서비스 작업 실행기와 같은 순서다. 지역 제안이 새로 추가한 여행지는 서비스처럼 실제로 저장된다(테스트 롤백과 무관).
        // 단계마다 걸린 시간을 재서 추천 대기 시간 제한(60초) 안에 끝나는지 본다.
        long started = System.nanoTime();
        try { regions.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 지역 제안 없이 계속한다. */ }
        long regionDone = System.nanoTime();
        try { purposes.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 목적 시설 없이 계속한다. */ }
        long purposeDone = System.nanoTime();
        try { picks.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 기존 기준으로 후보를 고른다. */ }
        long pickDone = System.nanoTime();
        for (var destination : places.findDestinations())
            profileByName.computeIfAbsent(destination.getName(), name -> profiles.profile(destination.getId()));
        var enriched = mapper.readValue(jobs.findById(id).orElseThrow().getRequestJson(), ConfirmedTravelConditions.class);
        recommendations.prepare(id);
        var work = ranking.claim(id);
        if (work == null) throw new IllegalStateException("추천 작업을 시작하지 못했어요: " + recommendations.view(id, null, hash).status());
        ranking.complete(work, ranking.generate(work));
        String interpreted = "지역 " + (blank(conditions.region()) ? "없음" : conditions.region())
            + " · 취향 " + (conditions.themeNames().isEmpty() ? "없음" : String.join("·", conditions.themeNames()))
            + " · 목적 " + (blank(conditions.travelPurpose()) ? "없음" : conditions.travelPurpose())
            + (enriched.purposeSearch() == null ? "" : " · 목적 검색어 " + String.join("·", enriched.purposeSearch().keywords()))
            + (enriched.regionSuggestion() == null ? "" : " · 지역 제안 " + enriched.regionSuggestion().status() + " "
                + enriched.regionSuggestion().destinationIds().stream().map(destinationId -> places.findById(destinationId).map(p -> p.getName()
                    + ("TOURAPI".equals(p.getSourceProvider()) ? "(추가)" : "")).orElse("?")).collect(Collectors.joining("·"))
                + " 분류 " + String.join("·", enriched.regionSuggestion().categories()))
            + (enriched.destinationPick() == null ? "" : " · AI 후보 고르기 " + enriched.destinationPick().status() + " "
                + enriched.destinationPick().destinationIds().stream().map(destinationId -> places.findById(destinationId).map(p -> p.getName()).orElse("?"))
                    .collect(Collectors.joining("·")))
            + String.format(" · 준비 시간 지역 제안 %.1f초·목적 시설 %.1f초·후보 고르기 %.1f초",
                (regionDone - started) / 1e9, (purposeDone - regionDone) / 1e9, (pickDone - purposeDone) / 1e9);
        // 여행지 전체를 이 시나리오의 근거 수로 줄 세운다.
        Map<String, Integer> counts = new LinkedHashMap<>();
        profileByName.forEach((name, profile) -> counts.put(name, DestinationProfile.count(profile, s.categories())));
        var top = counts.entrySet().stream().sorted(Map.Entry.<String, Integer>comparingByValue().reversed()).limit(5)
            .map(e -> e.getKey() + "(" + e.getValue() + ")").toList();
        List<Candidate> candidates = new ArrayList<>();
        for (var ranked : recommendations.view(id, null, hash).recommendations()) {
            String name = ranked.destination().name();
            int evidence = counts.getOrDefault(name, 0);
            int evidenceRank = 1 + (int) counts.values().stream().filter(c -> c > evidence).count();
            var names = DestinationProfile.names(profileByName.getOrDefault(name, Map.of()), s.categories(), 4);
            candidates.add(new Candidate(ranked.rank(), name, evidence, evidenceRank, names, ranked.reason(),
                (int) HEDGE.matcher(ranked.reason() == null ? "" : ranked.reason()).results().count()));
        }
        return new Measured(s, interpreted, candidates, top, null);
    }

    private Path writeReport(List<Measured> measured) throws Exception {
        var now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        var out = new StringBuilder();
        out.append("# 여행지 추천 품질 측정 (").append(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(")\n\n")
            .append("자연어 한 문장으로 추천받은 결과다. 근거는 여행지별 관광공사 소분류 장소 수다. '근거 상위 5와 겹침'은 여행지 ")
            .append(profileByName.size()).append("곳을 근거 수로 줄 세운 상위 5곳과 추천 5곳이 겹치는 수, '1순위 근거 순위'는 1순위 여행지가 ")
            .append("그 줄에서 몇 위인지다. '확인 필요 문구'는 추천 이유의 '확인이 필요·확인하지 못·찾지 못' 같은 표현 수다.\n\n")
            .append("| 시나리오 | 근거 상위 5와 겹침 | 1순위 근거 순위 | 근거 있는 후보 | 확인 필요 문구(이유 수) | 추천 순위 (근거 수) |\n|---|---|---|---|---|---|\n");
        int overlap = 0, hedgedReasons = 0, reasons = 0, withEvidence = 0;
        for (var m : measured) {
            if (m.error() != null) { out.append("| ").append(m.scenario().name()).append(" | 실패: ").append(m.error().replace("|", "/")).append(" |||||\n"); continue; }
            var c = m.candidates();
            var topNames = m.evidenceTop().stream().map(t -> t.substring(0, t.indexOf('('))).collect(Collectors.toSet());
            long hit = c.stream().filter(x -> topNames.contains(x.name())).count();
            long ok = c.stream().filter(x -> x.evidence() > 0).count();
            long hedged = c.stream().filter(x -> x.hedges() > 0).count();
            overlap += hit; withEvidence += ok; hedgedReasons += hedged; reasons += c.size();
            out.append("| ").append(m.scenario().name())
                .append(" | ").append(hit).append("/5")
                .append(" | ").append(c.stream().filter(x -> x.rank() == 1).findFirst().map(x -> x.evidenceRank() + "위").orElse("-"))
                .append(" | ").append(ok).append("/").append(c.size())
                .append(" | ").append(c.stream().mapToInt(Candidate::hedges).sum()).append(" (").append(hedged).append("/").append(c.size()).append(")")
                .append(" | ").append(c.stream().map(x -> x.rank() + "." + x.name() + "(" + x.evidence() + ")").collect(Collectors.joining(" "))).append(" |\n");
        }
        out.append("\n합계: 근거 상위 5와 겹침 ").append(overlap).append("/").append(measured.stream().filter(m -> m.error() == null).count() * 5)
            .append(", 근거 있는 후보 ").append(withEvidence).append("/").append(reasons)
            .append(", 확인 필요 문구가 있는 이유 ").append(hedgedReasons).append("/").append(reasons).append(".\n");
        for (var m : measured) {
            if (m.error() != null) continue;
            out.append("\n## ").append(m.scenario().name()).append("\n\n")
                .append("- 입력: ").append(m.scenario().text()).append("\n")
                .append("- 해석: ").append(m.interpreted()).append("\n")
                .append("- 근거 분류: ").append(String.join(", ", m.scenario().categories())).append("\n")
                .append("- 근거 상위 5: ").append(String.join(" · ", m.evidenceTop())).append("\n");
            for (var c : m.candidates())
                out.append("- ").append(c.rank()).append(". ").append(c.name()).append(" — 근거 ").append(c.evidence()).append("곳(전체 ")
                    .append(c.evidenceRank()).append("위)").append(c.evidenceNames().isEmpty() ? "" : " " + String.join("·", c.evidenceNames()))
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
