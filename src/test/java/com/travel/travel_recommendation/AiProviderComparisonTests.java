package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.ConfirmedTravelConditions;
import com.travel.travel_recommendation.dto.TravelConditionForm;
import com.travel.travel_recommendation.repository.AiJobRepository;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * GPT와 Claude 비교 측정. 같은 여행 문장을 두 AI에 각각 넣어 홈 화면의 "이 문장으로 여행지 추천받기"부터
 * 1순위 여행지의 일정 초안까지 만들고, 해석한 조건·추천·일정·걸린 시간을 target/quality/에 남긴다.
 * 두 AI 모두 같은 지시문·출력 형식을 받고, 장소 후보도 같은 DB 자료를 쓴다(같은 트랜잭션에서 차례로 실행).
 * 유료 API를 쓰므로 RUN_AI_COMPARISON=true일 때만 실행한다. 테스트 트랜잭션은 끝나면 롤백한다.
 * COMPARISON_SCENARIO에 이름 일부(예: 부산)를 넣으면 그 문장만, COMPARISON_PROVIDER에 GPT나 Claude를 넣으면 그 AI만 잰다.
 */
@EnabledIfEnvironmentVariable(named="RUN_AI_COMPARISON",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false",
    "travel.openai.api-key=${OPENAI_API_KEY:${OPEN_AI_API:${Portfolio_java_API:}}}","travel.openai.extra-api-keys=${Portfolio_java_API:}",
    "travel.claude.api-key=${CLAUDE_API_KEY:${ANTHROPIC_API_KEY:}}","travel.claude.timeout-seconds=120",
    "travel.kakao.rest-api-key=${KAKAO_REST_API_KEY:}",
    "travel.tourapi.service-key=${TOUR_API_SERVICE_KEY:}","travel.tago.service-key=${TAGO_SERVICE_KEY:${TOUR_API_SERVICE_KEY:}}"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class AiProviderComparisonTests {
    @Autowired TravelConditionOptions options;
    @Autowired TravelConditionService drafts;
    @Autowired ConditionReviewService reviews;
    @Autowired RecommendationJobService recommendations;
    @Autowired RegionSuggestionService regions;
    @Autowired PurposePlaceService purposes;
    @Autowired DestinationPickService picks;
    @Autowired DestinationRecommendationService ranking;
    @Autowired PlaceDiscoveryService discovery;
    @Autowired ItineraryJobService schedules;
    @Autowired PlacePickService placePicks;
    @Autowired NearbyRestaurantService restaurants;
    @Autowired AiJobRepository jobs;
    @Autowired ClaudeFallbackClient claude;
    @Autowired ObjectMapper mapper;
    @Autowired EntityManager em;

    record Scenario(String name, String text) {}
    /** 2026-11-13 실사용 질문을 바탕으로 날짜를 넣은 문장. 두 AI에 글자 그대로 같은 문장을 준다. */
    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("부모님 2박 3일", "12월 12일부터 60대 부모님 모시고 2박 3일 여행 가려고 해요. 서울에서 차로 출발하고, 부모님이 무릎이 안 좋으셔서 많이 걷는 곳은 빼 주세요. 맛있는 한식집이랑 경치 좋은 곳 위주로 부탁해요."),
        new Scenario("혼자 바다 1박 2일", "12월 5일에 혼자 1박 2일로 바다 보면서 조용히 쉬다 오고 싶어요. 차가 없어서 서울에서 KTX나 버스로 갈 수 있는 곳이면 좋겠고, 혼밥하기 편한 맛집도 넣어 주세요. 예산은 25만 원 정도예요."),
        new Scenario("부산 친구 2박 3일", "대학 동기 5명이랑 12월 18일부터 부산으로 2박 3일 놀러 가요. 대중교통으로 다닐 거고, 사진 찍기 좋은 곳이랑 밤에 놀 만한 곳, 현지 맛집 위주로 일정 짜 주세요."),
        new Scenario("양평 당일 드라이브", "12월 6일에 여자친구랑 양평으로 당일치기 드라이브 가고 싶어요. 분위기 좋은 카페랑 예쁜 풍경 보이는 곳 추천해 주세요."),
        new Scenario("경주 아이 1박 2일", "초등학교 4학년 아들이랑 역사 공부도 할 겸 12월 12일부터 경주로 1박 2일 가려고 해요. 대전에서 차로 가고, 아이가 지루해하지 않게 체험할 수 있는 곳도 넣어 주세요."),
        new Scenario("겨울 바다 대게", "12월 19일부터 1박 2일로 친구 셋이서 겨울 바다 보고 대게 먹으러 가고 싶어. 조용한 곳이면 좋겠고 자동차로 갈 거야."));
    /** 문장에서 출발일을 읽지 못했을 때만 쓰는 날짜. */
    static final LocalDate FALLBACK_START = LocalDate.of(2026, 12, 12);

    /** 한 AI가 한 문장으로 만든 결과. 실패하면 error에 어느 단계에서 왜 멈췄는지 적는다. */
    static class Outcome {
        String provider, error, interpreted = "", notices = "", assist = "";
        List<String> ranked = new ArrayList<>(), days = new ArrayList<>();
        Map<String, Double> seconds = new LinkedHashMap<>();
        boolean pickedByAi;
        int claudeAnswers;
    }

    @Test void comparesGptAndClaude() throws Exception {
        assertThat(claude.configured()).as("Claude 키(CLAUDE_API_KEY)가 필요합니다").isTrue();
        String only = System.getenv("COMPARISON_SCENARIO");
        var out = new StringBuilder("# GPT·Claude 비교 측정 (")
            .append(LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))).append(")\n\n");
        int finished = 0;
        for (var scenario : SCENARIOS) {
            if (only != null && !only.isBlank() && !scenario.name().contains(only)) continue;
            out.append("## ").append(scenario.name()).append("\n\n> ").append(scenario.text()).append("\n\n");
            for (boolean useClaude : List.of(false, true)) {
                String provider = System.getenv("COMPARISON_PROVIDER");
                if (provider != null && !provider.isBlank() && !provider.equalsIgnoreCase(useClaude ? "Claude" : "GPT")) continue;
                claude.usePrimary(useClaude);
                int before = claude.answerCount();
                var outcome = new Outcome();
                outcome.provider = useClaude ? "Claude" : "GPT";
                try { run(scenario, outcome); finished++; }
                catch (Exception error) { outcome.error = error.getClass().getSimpleName() + ": " + error.getMessage(); }
                finally { claude.usePrimary(false); }
                outcome.claudeAnswers = claude.answerCount() - before;
                write(out, outcome);
            }
        }
        var folder = Path.of("target", "quality");
        Files.createDirectories(folder);
        var file = folder.resolve("ai-comparison-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, out.toString(), StandardCharsets.UTF_8);
        System.out.println("GPT·Claude 비교 보고서: " + file.toAbsolutePath());
        assertThat(finished).isPositive();
    }

    /** 서비스 작업 실행기와 같은 순서: 조건 분석 → 지역 제안·목적 시설·후보 고르기 → 순위 → 1순위 여행지 장소 찾기 → 관광지 고르기 → 일정. */
    private void run(Scenario s, Outcome o) throws Exception {
        var choices = options.getChoices();
        var form = new TravelConditionForm();
        form.setInputMode("TEXT"); form.setOriginalText(s.text());
        var source = drafts.createDraft(form, choices);
        long t = System.nanoTime();
        var analysis = reviews.analyze(source, choices);
        t = lap(o, "조건 분석", t);
        o.notices = String.join(" / ", analysis.notices())
            + (analysis.followUps().isEmpty() ? "" : " [되묻기] " + analysis.followUps().stream().map(f -> f.question()).toList());
        ConfirmedTravelConditions c = reviews.confirm(analysis.form(), source, analysis.summary(), choices).forDirectRecommendation();
        o.interpreted = "지역 " + text(c.region()) + " · " + c.startDate() + "~" + c.endDate() + " · " + c.durationDays() + "일 · 인원 " + c.partySize()
            + " · 예산 " + c.budget() + " · 숙박 " + c.accommodationNeeded()
            + " · 키워드 " + c.selectedKeywords().stream().map(k -> k.name()).toList()
            + " · 목적 " + text(c.travelPurpose()) + " · 제외 " + c.excludedRegions() + "\n  - 해석 요약: " + text(c.interpretationSummary());

        byte[] hash = MessageDigest.getInstance("SHA-256").digest(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
        UUID id = recommendations.create(c, null, hash);
        try { regions.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 지역 제안 없이 계속한다. */ }
        try { purposes.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 목적 시설 없이 계속한다. */ }
        try { picks.enrich(id); } catch (RuntimeException skipped) { /* 서비스처럼 기존 기준으로 후보를 고른다. */ }
        t = lap(o, "추천 준비", t);
        recommendations.prepare(id);
        var work = ranking.claim(id);
        if (work == null) throw new IllegalStateException("추천 작업을 시작하지 못했어요: " + recommendations.view(id, null, hash).status());
        ranking.complete(work, ranking.generate(work));
        t = lap(o, "여행지 순위", t);
        var ranked = recommendations.view(id, null, hash).recommendations();
        for (var r : ranked) o.ranked.add(r.rank() + ". " + r.destination().name() + " — " + r.reason());
        if (ranked.isEmpty()) throw new IllegalStateException("추천된 여행지가 없어요.");

        // 화면에서 1순위 여행지의 '일정 만들기'를 누른 것과 같다. 시간·체류시간은 화면 기본값을 쓴다.
        long destination = ranked.getFirst().destination().placeId();
        em.flush();
        discovery.refresh(id, destination, null, hash);
        em.flush();
        t = lap(o, "장소 찾기(AI 아님)", t);
        var defaults = schedules.options(id, destination, null, hash);
        UUID job = schedules.create(id, destination, null, hash, defaults.start(), defaults.end(), defaults.transport(), defaults.visitMinutes(),
            null, c.durationDays() == null ? 2 : c.durationDays(), c.accommodationNeeded(), c.startDate() == null ? FALLBACK_START : null);
        try { placePicks.enrich(job); } catch (RuntimeException skipped) { /* 서버 규칙으로 고른 관광지를 그대로 쓴다. */ }
        t = lap(o, "관광지 고르기", t);
        try { restaurants.enrich(job); } catch (RuntimeException skipped) { /* 기존 음식점 후보를 쓴다. */ }
        var picking = schedules.view(job, null, hash).input().picking();
        o.pickedByAi = picking != null && picking.byAi();
        var itinerary = schedules.claim(job);
        if (itinerary == null) throw new IllegalStateException("일정 작업을 시작하지 못했어요.");
        t = System.nanoTime();
        var result = schedules.generate(itinerary);
        schedules.complete(itinerary, result);
        em.flush();
        lap(o, "일정 만들기", t);
        for (var day : result.days()) {
            var line = new StringBuilder(day.number() + "일차(" + day.date() + ", " + result.transport() + ")");
            for (var item : day.items())
                line.append("\n    - ").append(item.start()).append("~").append(item.end()).append(" [").append(item.type()).append("] ").append(item.name())
                    .append(item.note() == null || item.note().isBlank() ? "" : " — " + item.note().replace("\n", " / "));
            o.days.add(line.toString());
        }
        if (result.assist() != null) o.assist = text(result.assist().summary());
        // 일정 원본도 남겨 필요할 때 자세히 본다.
        var folder = Path.of("target", "quality", "ai-comparison");
        Files.createDirectories(folder);
        Files.writeString(folder.resolve(s.name().replace(" ", "_") + "-" + o.provider + ".json"), mapper.writeValueAsString(result), StandardCharsets.UTF_8);
    }

    private static long lap(Outcome o, String stage, long startedAt) {
        o.seconds.put(stage, (System.nanoTime() - startedAt) / 1e9);
        return System.nanoTime();
    }

    private static String text(String value) { return value == null || value.isBlank() ? "없음" : value; }

    private static void write(StringBuilder out, Outcome o) {
        out.append("### ").append(o.provider).append("\n\n");
        out.append("- 해석: ").append(o.interpreted).append("\n");
        if (!o.notices.isBlank()) out.append("- 안내·되묻기: ").append(o.notices).append("\n");
        out.append("- 걸린 시간(초): ");
        o.seconds.forEach((stage, seconds) -> out.append(stage).append(' ').append(String.format("%.1f", seconds)).append(" · "));
        out.append("Claude 응답 ").append(o.claudeAnswers).append("회\n");
        if (o.error != null) out.append("- **실패**: ").append(o.error).append("\n");
        if (!o.ranked.isEmpty()) { out.append("- 추천:\n"); o.ranked.forEach(r -> out.append("  - ").append(r).append("\n")); }
        if (!o.days.isEmpty()) {
            out.append("- 일정(관광지 순서를 AI가 정함: ").append(o.pickedByAi).append("):\n");
            o.days.forEach(d -> out.append("  - ").append(d).append("\n"));
        }
        if (!o.assist.isBlank()) out.append("- AI 안내: ").append(o.assist).append("\n");
        out.append("\n");
    }
}
