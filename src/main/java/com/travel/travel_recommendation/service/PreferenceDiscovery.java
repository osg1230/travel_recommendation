package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 선택형 여행 취향 탐색(SFR-02-05·06). 여행 장면을 고르는 질문과, 선택지에 붙인 태그의 단순 점수 규칙으로
 * 이번 여행의 취향을 도출한다. AI를 쓰지 않으므로 같은 선택에는 항상 같은 결과가 나온다.
 * 태그는 여행 취향(THEME) 키워드의 값(NATURE·SEA·REST·FOOD·CULTURE·ACTIVITY)과 같다.
 */
public final class PreferenceDiscovery {
    private PreferenceDiscovery() {}

    public static final String NO_PREFERENCE = "ANY";
    public static final String SKIP = "SKIP";

    public record Option(String id, String scene, List<String> tags) {}
    public record Question(String id, String prompt, List<Option> options) {}
    /** 도출된 취향 하나. reasons는 이 취향이 나온 선택 장면이다. */
    public record Preference(String tag, String name, int score, List<String> reasons) {}
    public record Result(List<Preference> preferences, int answered, int skipped) {
        public boolean empty() { return preferences.isEmpty(); }
    }

    /** 태그별 화면 이름. DB 키워드 이름과 같으며, 예시 목록(DB 없음)에서도 이 이름으로 찾는다. */
    public static final Map<String, String> TAG_NAMES = Map.of("NATURE", "자연", "SEA", "바다", "REST", "휴식",
        "FOOD", "맛집", "CULTURE", "문화·역사", "ACTIVITY", "체험·액티비티");
    private static final List<String> TAG_ORDER = List.of("SEA", "NATURE", "REST", "FOOD", "CULTURE", "ACTIVITY");

    public static final List<Question> QUESTIONS = List.of(
        new Question("Q1", "여행지에 도착한 첫 오후, 어느 쪽이 더 끌리나요?", List.of(
            new Option("A", "바다가 보이는 카페에서 느긋하게 쉬기", List.of("SEA", "REST")),
            new Option("B", "시장과 골목을 걸으며 먹거리 찾기", List.of("FOOD", "CULTURE")))),
        new Question("Q2", "아침 시간을 쓴다면?", List.of(
            new Option("A", "숲길이나 호숫가를 천천히 산책하기", List.of("NATURE", "REST")),
            new Option("B", "레일바이크·서핑처럼 몸을 쓰는 체험하기", List.of("ACTIVITY")))),
        new Question("Q3", "하루 중 한 곳만 꼭 간다면?", List.of(
            new Option("A", "오래된 유적이나 박물관을 차분히 둘러보기", List.of("CULTURE")),
            new Option("B", "해변을 걷다가 노을 보기", List.of("SEA", "NATURE")))),
        new Question("Q4", "저녁은 어떻게 보내고 싶나요?", List.of(
            new Option("A", "현지에서 유명한 식당 찾아가기", List.of("FOOD")),
            new Option("B", "숙소에서 일찍 쉬며 다음 날 준비하기", List.of("REST")))),
        new Question("Q5", "이번 여행의 속도는?", List.of(
            new Option("A", "여러 곳을 부지런히 다니기", List.of("ACTIVITY")),
            new Option("B", "적게 가더라도 한곳에 오래 머물기", List.of("REST")))));

    /**
     * 답변은 질문 ID → 선택지 ID(A·B), 상관없음(ANY), 건너뛰기(SKIP)이다. 없는 질문·선택지는 거부한다.
     * 선택을 바꾸면 이전 점수를 누적하지 않도록 매번 답변 전체로 다시 계산한다.
     */
    public static Result evaluate(Map<String, String> answers) {
        if (answers == null) answers = Map.of();
        for (var entry : answers.entrySet()) {
            var question = question(entry.getKey());
            String value = entry.getValue();
            if (question == null || value == null || !(NO_PREFERENCE.equals(value) || SKIP.equals(value)
                    || question.options().stream().anyMatch(o -> o.id().equals(value))))
                throw new IllegalArgumentException("Unknown question or option");
        }
        Map<String, Integer> scores = new HashMap<>(); Map<String, List<String>> reasons = new HashMap<>();
        int answered = 0, skipped = 0;
        for (var question : QUESTIONS) {
            String value = answers.getOrDefault(question.id(), SKIP);
            if (SKIP.equals(value)) { skipped++; continue; }
            answered++;
            // 상관없음은 답한 것으로 세되 어떤 취향에도 점수를 주지 않는다. 고르지 않은 쪽을 비선호로 보지 않는다.
            if (NO_PREFERENCE.equals(value)) continue;
            var option = question.options().stream().filter(o -> o.id().equals(value)).findFirst().orElseThrow();
            for (String tag : option.tags()) {
                scores.merge(tag, 1, Integer::sum);
                reasons.computeIfAbsent(tag, t -> new ArrayList<>()).add(option.scene());
            }
        }
        var preferences = scores.entrySet().stream()
            .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                .thenComparingInt(e -> TAG_ORDER.indexOf(e.getKey())))
            .map(e -> new Preference(e.getKey(), TAG_NAMES.get(e.getKey()), e.getValue(), List.copyOf(reasons.get(e.getKey()))))
            .toList();
        return new Result(preferences, answered, skipped);
    }

    public static Question question(String id) {
        return QUESTIONS.stream().filter(q -> q.id().equals(id)).findFirst().orElse(null);
    }
}
