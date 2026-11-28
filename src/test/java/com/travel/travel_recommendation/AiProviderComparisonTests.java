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
import java.util.Objects;
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
 * 모델과 생각 깊이는 OPENAI_MODEL·OPENAI_REASONING_EFFORT·ANTHROPIC_MODEL·ANTHROPIC_EFFORT로 바꾼다(버전별 비교).
 * COMPARISON_LABEL을 주면 target/quality/variants/{label}.md에 쓰고, COMPARISON_REPEAT(기본 1)만큼 조건 분석을 되풀이해 채점한다.
 * COMPARISON_SET=NEW면 새 문장 4개를 쓰고, COMPARISON_RUNS(기본 1)만큼 추천과 일정을 처음부터 다시 만든다.
 * 채점은 문장마다 정해 둔 정답(출발일·기간·인원·지역·예산·키워드·제외 지역)과 해석한 조건을 비교한다.
 */
@EnabledIfEnvironmentVariable(named="RUN_AI_COMPARISON",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false",
    "travel.openai.api-key=${OPENAI_API_KEY:${OPEN_AI_API:${Portfolio_java_API:}}}","travel.openai.extra-api-keys=${Portfolio_java_API:}",
    "travel.claude.api-key=${CLAUDE_API_KEY:${ANTHROPIC_API_KEY:}}","travel.claude.timeout-seconds=300",
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

    /**
     * 정답. start가 ""이면 날짜를 비우는 것이 정답이고, party·budget이 null이면 비우는 것이 정답이다.
     * region이 ""이면 지역 없음이 정답, "?"이면 채점하지 않는다. excluded는 꼭 들어가야 하는 제외 지역이다.
     */
    record Expect(String start, int days, Integer party, String region, Long budget, List<String> keywords, String excluded) {}
    /** itinerary가 true인 문장만 일정 초안까지 만든다. 나머지는 조건 분석만 채점한다. */
    record Scenario(String name, String text, boolean itinerary, Expect expect) {}
    /** 앞 6개는 2026-11-13 실사용 질문에 날짜를 넣은 문장, 뒤 4개는 지시문을 고칠 때 쓰지 않은 새 문장이다. */
    static final List<Scenario> SCENARIOS = List.of(
        new Scenario("부모님 2박 3일", "12월 12일부터 60대 부모님 모시고 2박 3일 여행 가려고 해요. 서울에서 차로 출발하고, 부모님이 무릎이 안 좋으셔서 많이 걷는 곳은 빼 주세요. 맛있는 한식집이랑 경치 좋은 곳 위주로 부탁해요.",
            true, new Expect("2026-12-12", 3, 3, "", null, List.of("자차", "가족", "맛집", "느긋하게"), "")),
        new Scenario("혼자 바다 1박 2일", "12월 5일에 혼자 1박 2일로 바다 보면서 조용히 쉬다 오고 싶어요. 차가 없어서 서울에서 KTX나 버스로 갈 수 있는 곳이면 좋겠고, 혼밥하기 편한 맛집도 넣어 주세요. 예산은 25만 원 정도예요.",
            true, new Expect("2026-12-05", 2, 1, "", 250000L, List.of("대중교통", "혼자", "바다", "맛집", "한적한"), "")),
        new Scenario("부산 친구 2박 3일", "대학 동기 5명이랑 12월 18일부터 부산으로 2박 3일 놀러 가요. 대중교통으로 다닐 거고, 사진 찍기 좋은 곳이랑 밤에 놀 만한 곳, 현지 맛집 위주로 일정 짜 주세요.",
            true, new Expect("2026-12-18", 3, 6, "부산", null, List.of("대중교통", "친구", "맛집", "활기찬"), "")),
        new Scenario("양평 당일 드라이브", "12월 6일에 여자친구랑 양평으로 당일치기 드라이브 가고 싶어요. 분위기 좋은 카페랑 예쁜 풍경 보이는 곳 추천해 주세요.",
            true, new Expect("2026-12-06", 1, 2, "양평", null, List.of("자차", "연인", "감성적인"), "")),
        new Scenario("경주 아이 1박 2일", "초등학교 4학년 아들이랑 역사 공부도 할 겸 12월 12일부터 경주로 1박 2일 가려고 해요. 대전에서 차로 가고, 아이가 지루해하지 않게 체험할 수 있는 곳도 넣어 주세요.",
            true, new Expect("2026-12-12", 2, 2, "경주", null, List.of("자차", "가족", "문화·역사", "체험·액티비티"), "")),
        new Scenario("겨울 바다 대게", "12월 19일부터 1박 2일로 친구 셋이서 겨울 바다 보고 대게 먹으러 가고 싶어. 조용한 곳이면 좋겠고 자동차로 갈 거야.",
            true, new Expect("2026-12-19", 2, 3, "", null, List.of("자차", "친구", "바다", "맛집", "한적한"), "")),
        new Scenario("강원 눈 구경(새 문장)", "1월 2일부터 2박 3일로 아내랑 6살 딸이랑 셋이서 강원도로 눈 구경 가고 싶어요. 1인당 20만 원 정도 생각하고 있고 기차 타고 갈게요.",
            false, new Expect("2027-01-02", 3, 3, "강원", 600000L, List.of("대중교통", "가족", "겨울"), "")),
        new Scenario("부산 빼고 맛집 당일(새 문장)", "부산은 작년에 가 봐서 빼고, 12월 26일에 친구들이랑 당일치기로 맛집 투어 하고 싶어. 버스 타고 갈 거야.",
            false, new Expect("2026-12-26", 1, null, "", null, List.of("대중교통", "친구", "맛집"), "부산")),
        new Scenario("제주 혼자 3박 4일(새 문장)", "이번 겨울에 혼자 제주도 3박 4일 가려고. 렌터카 빌려서 오름이랑 조용한 카페 다니고 싶어. 예산은 80만 원.",
            false, new Expect("", 4, 1, "제주", 800000L, List.of("자차", "혼자", "겨울", "한적한"), "")),
        new Scenario("해돋이 1박 2일(새 문장)", "12월 31일에 남편이랑 해돋이 보러 1박 2일로 동해 쪽 가고 싶어요. 차로 갈 거고 숙소는 바다 보이는 곳이면 좋겠어요.",
            false, new Expect("2026-12-31", 2, 2, "?", null, List.of("자차"), "")));
    /** 선정한 모델로 일정을 되풀이해 만들어 볼 때 쓰는 새 문장(COMPARISON_SET=NEW). 앞의 비교와 지시문 수정에 쓰지 않은 문장이다. */
    static final List<Scenario> NEW_SCENARIOS = List.of(
        new Scenario("크리스마스 연인 1박 2일", "12월 24일부터 1박 2일로 남자친구랑 크리스마스 분위기 나는 곳으로 가고 싶어요. 서울에서 기차로 갈 수 있으면 좋겠고, 야경 예쁜 곳이랑 분위기 좋은 식당 넣어 주세요. 둘이 합쳐서 40만 원 정도 쓸 수 있어요.",
            true, new Expect("2026-12-24", 2, 2, "", 400000L, List.of("연인", "대중교통"), "")),
        new Scenario("전주 가족 당일", "1월 10일에 시부모님이랑 남편, 저 이렇게 넷이서 전주로 당일치기 다녀오려고요. 차로 가고 한옥마을 구경하고 비빔밥 먹고 싶어요.",
            true, new Expect("2027-01-10", 1, 4, "전주", null, List.of("가족", "자차"), "")),
        new Scenario("엄마와 온천 2박 3일", "12월 27일부터 2박 3일로 엄마랑 둘이 온천 하면서 푹 쉬고 싶어요. 운전은 제가 하고, 많이 돌아다니지 않고 맛있는 거 먹는 여행이면 좋겠어요.",
            true, new Expect("2026-12-27", 3, 2, "", null, List.of("가족", "자차"), "")),
        new Scenario("여수 친구 1박 2일", "1월 16일부터 1박 2일로 고등학교 친구 3명이랑 여수 가요. KTX 타고 가서 버스로 다닐 거고, 케이블카 타고 밤바다 보고 해산물 먹는 게 목표예요. 1인당 15만 원씩 잡았어요.",
            true, new Expect("2027-01-16", 2, 4, "여수", 600000L, List.of("친구", "대중교통"), "")));
    /** 이번 실행의 비회원 구분값. 실행마다 달라 예전 실행의 작업과 섞이지 않는다. */
    static final String RUN_ID = UUID.randomUUID().toString();
    /** 문장에서 출발일을 읽지 못했을 때만 쓰는 날짜. */
    static final LocalDate FALLBACK_START = LocalDate.of(2026, 12, 12);

    /** 한 AI가 한 문장으로 만든 결과. 실패하면 error에 어느 단계에서 왜 멈췄는지 적는다. */
    static class Outcome {
        String provider, error, interpreted = "", notices = "", assist = "";
        List<String> ranked = new ArrayList<>(), days = new ArrayList<>();
        Map<String, Double> seconds = new LinkedHashMap<>();
        boolean pickedByAi;
        int claudeAnswers;
        /** 조건 분석 채점: 되풀이한 횟수만큼 더한 점수와 만점, 틀린 항목. */
        double score, maxScore;
        List<String> misses = new ArrayList<>();
    }

    @Test void comparesGptAndClaude() throws Exception {
        assertThat(claude.configured()).as("Claude 키(CLAUDE_API_KEY)가 필요합니다").isTrue();
        String only = System.getenv("COMPARISON_SCENARIO"), label = System.getenv("COMPARISON_LABEL");
        String repeatText = System.getenv("COMPARISON_REPEAT");
        int repeat = repeatText == null || repeatText.isBlank() ? 1 : Integer.parseInt(repeatText);
        // COMPARISON_RUNS(기본 1)만큼 같은 문장으로 추천과 일정을 처음부터 다시 만든다. 실행마다 결과가 얼마나 달라지는지 본다.
        String runsText = System.getenv("COMPARISON_RUNS");
        int runs = runsText == null || runsText.isBlank() ? 1 : Integer.parseInt(runsText);
        var out = new StringBuilder();
        int finished = 0, planned = 0, errors = 0;
        double score = 0, maxScore = 0, aiSeconds = 0;
        for (var scenario : "NEW".equalsIgnoreCase(System.getenv("COMPARISON_SET")) ? NEW_SCENARIOS : SCENARIOS) {
            if (only != null && !only.isBlank() && !scenario.name().contains(only)) continue;
            out.append("## ").append(scenario.name()).append("\n\n> ").append(scenario.text()).append("\n\n");
            for (boolean useClaude : List.of(false, true)) for (int round = 1; round <= runs; round++) {
                String provider = System.getenv("COMPARISON_PROVIDER");
                if (provider != null && !provider.isBlank() && !provider.equalsIgnoreCase(useClaude ? "Claude" : "GPT")) continue;
                claude.usePrimary(useClaude);
                int before = claude.answerCount();
                var outcome = new Outcome();
                outcome.provider = (useClaude ? "Claude" : "GPT") + (runs > 1 ? " " + round + "회차" : "");
                if (scenario.itinerary()) planned++;
                try {
                    run(scenario, outcome);
                    if (scenario.itinerary()) finished++;
                }
                catch (Exception error) { outcome.error = error.getClass().getSimpleName() + ": " + error.getMessage(); errors++; }
                // 같은 문장을 더 분석해 해석이 실행마다 흔들리는지 본다.
                for (int i = 1; i < repeat; i++) {
                    try { analyze(scenario, outcome); }
                    catch (Exception error) { outcome.maxScore += maxScore(scenario.expect()); outcome.misses.add("분석 실패"); }
                }
                claude.usePrimary(false);
                // 첫 분석부터 실패했으면 그 한 번을 0점으로 센다.
                if (outcome.maxScore < maxScore(scenario.expect()) * repeat) { outcome.maxScore += maxScore(scenario.expect()); outcome.misses.add("분석 실패"); }
                outcome.claudeAnswers = claude.answerCount() - before;
                score += outcome.score; maxScore += outcome.maxScore;
                aiSeconds += outcome.seconds.entrySet().stream().filter(e -> !e.getKey().contains("AI 아님")).mapToDouble(Map.Entry::getValue).sum();
                write(out, outcome);
            }
        }
        String summary = String.format("SUMMARY label=%s understanding=%.1f/%.0f itineraries=%d/%d errors=%d aiSeconds=%.0f",
            label == null ? "-" : label, score, maxScore, finished, planned, errors, aiSeconds);
        boolean variant = label != null && !label.isBlank();
        var folder = variant ? Path.of("target", "quality", "variants") : Path.of("target", "quality");
        Files.createDirectories(folder);
        var file = folder.resolve(variant ? label + ".md"
            : "ai-comparison-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")) + ".md");
        Files.writeString(file, "# GPT·Claude 비교 측정 (" + LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
            + ")\n\n" + summary + "\n\n" + out, StandardCharsets.UTF_8);
        System.out.println("GPT·Claude 비교 보고서: " + file.toAbsolutePath());
        System.out.println(summary);
    }

    /** 조건 분석만 하고 정답과 비교해 점수를 더한다. 확정한 조건을 돌려준다. */
    private ConfirmedTravelConditions analyze(Scenario s, Outcome o) {
        var choices = options.getChoices();
        var form = new TravelConditionForm();
        form.setInputMode("TEXT"); form.setOriginalText(s.text());
        var source = drafts.createDraft(form, choices);
        var analysis = reviews.analyze(source, choices);
        if (o.notices.isBlank()) o.notices = String.join(" / ", analysis.notices())
            + (analysis.followUps().isEmpty() ? "" : " [되묻기] " + analysis.followUps().stream().map(f -> f.question()).toList());
        ConfirmedTravelConditions c = reviews.confirm(analysis.form(), source, analysis.summary(), choices).forDirectRecommendation();
        grade(s.expect(), c, o);
        return c;
    }

    private static double maxScore(Expect e) { return 5 + ("?".equals(e.region()) ? 0 : 1) + (e.excluded().isBlank() ? 0 : 1); }

    /** 항목마다 1점(키워드는 맞힌 비율). 틀린 항목은 무엇으로 읽었는지 남긴다. */
    private static void grade(Expect e, ConfirmedTravelConditions c, Outcome o) {
        o.maxScore += maxScore(e);
        String start = c.startDate() == null ? "" : c.startDate().toString();
        if (start.equals(e.start())) o.score++; else o.misses.add("출발일 " + (start.isEmpty() ? "비움" : start));
        if (c.durationDays() != null && c.durationDays() == e.days()) o.score++; else o.misses.add("기간 " + c.durationDays());
        if (Objects.equals(c.partySize(), e.party())) o.score++; else o.misses.add("인원 " + c.partySize());
        String region = c.region() == null ? "" : c.region().strip();
        if (!"?".equals(e.region())) {
            if (e.region().isEmpty() ? region.isEmpty() : region.contains(e.region())) o.score++; else o.misses.add("지역 '" + region + "'");
        }
        Long budget = c.budget() == null ? null : c.budget().longValue();
        if (Objects.equals(budget, e.budget())) o.score++; else o.misses.add("예산 " + budget);
        var names = c.selectedKeywords().stream().map(k -> k.name()).toList();
        var missing = e.keywords().stream().filter(k -> !names.contains(k)).toList();
        o.score += (e.keywords().size() - missing.size()) / (double) e.keywords().size();
        if (!missing.isEmpty()) o.misses.add("키워드 빠짐 " + missing);
        if (!e.excluded().isBlank()) {
            if (c.excludedRegions().stream().anyMatch(r -> r.contains(e.excluded()))) o.score++; else o.misses.add("제외 지역 " + c.excludedRegions());
        }
    }

    /** 서비스 작업 실행기와 같은 순서: 조건 분석 → 지역 제안·목적 시설·후보 고르기 → 순위 → 1순위 여행지 장소 찾기 → 관광지 고르기 → 일정. */
    private void run(Scenario s, Outcome o) throws Exception {
        long t = System.nanoTime();
        ConfirmedTravelConditions c = analyze(s, o);
        t = lap(o, "조건 분석", t);
        o.interpreted = "지역 " + text(c.region()) + " · " + c.startDate() + "~" + c.endDate() + " · " + c.durationDays() + "일 · 인원 " + c.partySize()
            + " · 예산 " + c.budget() + " · 숙박 " + c.accommodationNeeded()
            + " · 키워드 " + c.selectedKeywords().stream().map(k -> k.name()).toList()
            + " · 목적 " + text(c.travelPurpose()) + " · 제외 " + c.excludedRegions() + "\n  - 해석 요약: " + text(c.interpretationSummary());
        if (!s.itinerary()) return;

        // 같은 문장·같은 AI의 회차는 같은 비회원(브라우저)으로 본다. 다시 추천받았을 때 예전 일정과 다른 관광지를 고르는지 보기 위해서다.
        byte[] hash = MessageDigest.getInstance("SHA-256").digest((RUN_ID + s.name() + o.provider.split(" ")[0]).getBytes(StandardCharsets.UTF_8));
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
        // 보고서에는 관광·식사·숙소 도착만 이름과 종류로 짧게 적는다. 전체 내용은 아래 JSON에 남긴다.
        for (var day : result.days()) {
            var line = new StringBuilder(day.number() + "일차(" + day.date() + ", " + result.transport() + ")");
            for (var item : day.items()) {
                boolean stayArrival = "STAY".equals(item.type()) && item.note() != null && item.note().contains("도착");
                if (!"VISIT".equals(item.type()) && !"MEAL".equals(item.type()) && !stayArrival) continue;
                String kind = item.note() == null ? "" : item.note().split("\n")[0].split(" / ")[0];
                line.append("\n    - ").append(item.start()).append(" [").append(item.type()).append("] ").append(item.name()).append(" (").append(kind).append(")");
            }
            o.days.add(line.toString());
        }
        if (result.assist() != null) o.assist = text(result.assist().summary());
        String label = System.getenv("COMPARISON_LABEL");
        var folder = Path.of("target", "quality", "ai-comparison", label == null || label.isBlank() ? "latest" : label);
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
        out.append(String.format("- 조건 분석 점수: %.1f/%.0f", o.score, o.maxScore)).append(o.misses.isEmpty() ? "" : " · 틀림 " + o.misses).append("\n");
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
