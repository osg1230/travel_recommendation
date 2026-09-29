package com.travel.travel_recommendation.service;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.MonthDay;
import java.util.*;
import java.util.random.RandomGenerator;

/**
 * 선택형 여행 취향 탐색(SFR-02-05·06). 여행 장면이나 평소 모습을 고르는 질문과, 선택지에 붙인 태그의 단순 점수 규칙으로
 * 이번 여행의 취향을 도출한다. AI를 쓰지 않으므로 같은 선택에는 항상 같은 결과가 나온다.
 * 질문은 자유로운 소재로 묻되, 태그는 여행 취향(THEME) 키워드의 값(NATURE·SEA·REST·FOOD·CULTURE·ACTIVITY)만 쓴다.
 * 결과가 조건 입력의 '여행 취향'으로 그대로 들어가야 하기 때문이다.
 *
 * 첫 질문은 시작 질문 중 하나를 무작위로 고르고(화면 담당), 그 뒤 질문은 앞선 답으로 정해진다.
 * 예를 들어 '여름'을 고르면 여름에 즐길 거리를 묻는 질문이 이어진다. 한 번에 최대 5개를 묻는다.
 */
public final class PreferenceDiscovery {
    private PreferenceDiscovery() {}

    public static final String NO_PREFERENCE = "ANY";
    public static final String SKIP = "SKIP";
    /** 한 번의 탐색에서 묻는 질문 수(요구분석서 3~5개). */
    public static final int MAX_QUESTIONS = 5;

    /** next는 이 선택지를 골랐을 때 이어서 물어볼 질문 ID이다. */
    public record Option(String id, String scene, List<String> tags, List<String> next) {}
    public record Question(String id, String prompt, List<Option> options) {}
    /** 도출된 취향 하나. reasons는 이 취향이 나온 선택 장면이다. */
    public record Preference(String tag, String name, int score, List<String> reasons) {}
    public record Result(List<Preference> preferences, int answered, int skipped) {
        public boolean empty() { return preferences.isEmpty(); }
    }
    /** 지난 답변 목록에 보여 줄 질문과 고른 답. step은 묻은 순서(0부터)이다. */
    public record AnswerSummary(int step, String prompt, String choice) {}

    /** 태그별 화면 이름. DB 키워드 이름과 같으며, 예시 목록(DB 없음)에서도 이 이름으로 찾는다. */
    public static final Map<String, String> TAG_NAMES = Map.of("NATURE", "자연", "SEA", "바다", "REST", "휴식",
        "FOOD", "맛집", "CULTURE", "문화·역사", "ACTIVITY", "체험·액티비티");
    private static final List<String> TAG_ORDER = List.of("SEA", "NATURE", "REST", "FOOD", "CULTURE", "ACTIVITY");

    private static Option option(String id, String scene, List<String> tags, String... next) {
        return new Option(id, scene, tags, List.of(next));
    }

    /** 첫 질문으로 나올 수 있는 질문. 앞 답에서 이어갈 질문이 없을 때도 이 순서로 묻는다. */
    public static final List<String> START_IDS = List.of("SEASON", "PHOTO", "DAY_OFF", "MEMORY", "RAIN", "PLAYLIST");

    public static final List<Question> QUESTIONS = List.of(
        // 시작 질문
        new Question("SEASON", "여름과 겨울 중 어느 쪽이 더 좋아요?", List.of(
            option("A", "여름, 시원한 물가에서 신나게 놀기", List.of("SEA", "ACTIVITY"), "SUMMER_DAY", "SUMMER_NIGHT"),
            option("B", "겨울, 찬 공기 속에서 따뜻하게 쉬기", List.of("NATURE", "REST"), "WINTER_DAY", "WINTER_NIGHT"))),
        new Question("PHOTO", "여행 사진첩에 가장 많이 남는 사진은?", List.of(
            option("A", "탁 트인 풍경 사진", List.of("NATURE", "SEA"), "VIEW_SPOT"),
            option("B", "그날 먹은 음식 사진", List.of("FOOD"), "MUST_EAT"))),
        new Question("DAY_OFF", "평소 쉬는 날엔 주로 뭘 하나요?", List.of(
            option("A", "밖에 나가 운동하거나 여기저기 돌아다니기", List.of("ACTIVITY"), "THRILL", "ANIMAL"),
            option("B", "집에서 영화 보며 푹 쉬기", List.of("REST"), "SECOND_MORNING", "INDOOR"))),
        new Question("MEMORY", "여행에서 오래 기억에 남는 순간은?", List.of(
            option("A", "처음 보는 풍경 앞에 섰을 때", List.of("NATURE"), "VIEW_SPOT", "WALK"),
            option("B", "그 동네 사람들이 사는 모습을 봤을 때", List.of("CULTURE"), "LOCAL_LIFE", "OLD_STREET"))),
        new Question("RAIN", "여행 중에 비가 온다면?", List.of(
            option("A", "우산 쓰고 비 오는 풍경 즐기기", List.of("NATURE", "REST"), "WALK", "NIGHT"),
            option("B", "박물관이나 실내 체험관으로 가기", List.of("CULTURE", "ACTIVITY"), "INDOOR", "ANIMAL"))),
        new Question("PLAYLIST", "여행길에 틀고 싶은 노래는?", List.of(
            option("A", "창문 열고 따라 부르는 신나는 노래", List.of("ACTIVITY", "SEA"), "THRILL", "SUMMER_DAY"),
            option("B", "창밖을 보며 듣는 잔잔한 노래", List.of("REST", "NATURE"), "WALK", "SECOND_MORNING"))),

        // 계절에서 이어지는 질문
        new Question("SUMMER_DAY", "여름 낮에 하고 싶은 건?", List.of(
            option("A", "해수욕장에서 물놀이나 서핑하기", List.of("SEA", "ACTIVITY"), "SEA_TIME"),
            option("B", "계곡 그늘에 발 담그고 쉬기", List.of("NATURE", "REST"), "WALK"))),
        new Question("SUMMER_NIGHT", "여름 저녁은 어떻게 보낼까요?", List.of(
            option("A", "야시장이나 해변 포차에서 먹거리 즐기기", List.of("FOOD", "SEA"), "MUST_EAT"),
            option("B", "선선한 강변을 산책하며 야경 보기", List.of("NATURE", "REST"), "NIGHT"))),
        new Question("WINTER_DAY", "겨울 낮에 하고 싶은 건?", List.of(
            option("A", "스키장이나 눈썰매장에서 신나게 놀기", List.of("ACTIVITY"), "THRILL"),
            option("B", "눈 쌓인 산사와 설경 명소를 천천히 걷기", List.of("NATURE", "CULTURE"), "MOUNTAIN"))),
        new Question("WINTER_NIGHT", "추운 겨울 저녁엔?", List.of(
            option("A", "온천이나 스파에서 몸 녹이기", List.of("REST"), "SECOND_MORNING"),
            option("B", "뜨끈한 국물 요리 맛집 찾아가기", List.of("FOOD"), "MUST_EAT"))),

        // 그 밖의 이어지는 질문
        new Question("VIEW_SPOT", "그 풍경은 어디에서 보고 싶나요?", List.of(
            option("A", "해 뜨는 바닷가", List.of("SEA"), "SEA_TIME"),
            option("B", "구름 걸린 산 정상", List.of("NATURE", "ACTIVITY"), "MOUNTAIN"))),
        new Question("MUST_EAT", "여행지에서 꼭 먹어 보고 싶은 건?", List.of(
            option("A", "그 지역에서만 먹는 향토 음식", List.of("FOOD", "CULTURE"), "LOCAL_LIFE"),
            option("B", "줄 서서 먹는 빵과 디저트", List.of("FOOD", "REST"), "AFTERNOON"))),
        new Question("THRILL", "짜릿함은 어느 정도가 좋아요?", List.of(
            option("A", "짚라인이나 패러글라이딩처럼 심장 뛰는 체험", List.of("ACTIVITY"), "SEA_TIME"),
            option("B", "자전거나 레일바이크 정도면 충분", List.of("ACTIVITY", "NATURE"), "WALK"))),
        new Question("SECOND_MORNING", "여행 둘째 날 아침은?", List.of(
            option("A", "늦잠 자고 느지막이 브런치 먹기", List.of("REST", "FOOD"), "AFTERNOON"),
            option("B", "일출 보러 일찍 나가기", List.of("NATURE", "SEA"), "VIEW_SPOT"))),
        new Question("LOCAL_LIFE", "그 동네 분위기를 느끼려면?", List.of(
            option("A", "오일장이나 전통시장 구경하기", List.of("FOOD", "CULTURE"), "SOUVENIR"),
            option("B", "동네 작은 책방이나 공방 찾아가기", List.of("CULTURE", "REST"), "AFTERNOON"))),
        new Question("OLD_STREET", "옛 정취를 느낀다면?", List.of(
            option("A", "한옥마을이나 옛 거리 걸어 보기", List.of("CULTURE"), "SOUVENIR"),
            option("B", "고즈넉한 정원과 연못가에서 쉬기", List.of("CULTURE", "REST"), "WALK"))),
        new Question("INDOOR", "실내에서 시간을 보낸다면?", List.of(
            option("A", "박물관이나 미술관에서 전시 보기", List.of("CULTURE"), "OLD_STREET"),
            option("B", "방탈출이나 만들기 체험하기", List.of("ACTIVITY", "CULTURE"), "SOUVENIR"))),
        new Question("WALK", "걷는 길을 고른다면?", List.of(
            option("A", "바다 옆 해안 산책로", List.of("SEA", "NATURE"), "SEA_TIME"),
            option("B", "숲속 휴양림 길", List.of("NATURE", "REST"), "MOUNTAIN"))),
        new Question("NIGHT", "해가 지면 무엇을 할까요?", List.of(
            option("A", "야경 명소 찾아가기", List.of("NATURE"), "VIEW_SPOT"),
            option("B", "동네 포차에서 한잔하기", List.of("FOOD"), "MUST_EAT"))),
        new Question("SEA_TIME", "바다에서 보내는 시간은?", List.of(
            option("A", "요트나 카약 같은 해양 체험하기", List.of("SEA", "ACTIVITY"), "ANIMAL"),
            option("B", "바다가 보이는 카페에서 느긋하게 쉬기", List.of("SEA", "REST"), "AFTERNOON"))),
        new Question("MOUNTAIN", "산에 간다면?", List.of(
            option("A", "케이블카를 타거나 정상에 올라 전망 보기", List.of("NATURE", "ACTIVITY"), "THRILL"),
            option("B", "오래된 산사와 문화재 둘러보기", List.of("NATURE", "CULTURE"), "OLD_STREET"))),
        new Question("AFTERNOON", "느긋한 오후를 보낸다면?", List.of(
            option("A", "전망 좋은 카페에서 책 읽기", List.of("REST"), "NIGHT"),
            option("B", "동네 빵집과 디저트 가게 찾아다니기", List.of("FOOD", "REST"), "LOCAL_LIFE"))),
        new Question("ANIMAL", "동물을 만날 수 있다면?", List.of(
            option("A", "아쿠아리움에서 바다 생물 보기", List.of("SEA", "CULTURE"), "INDOOR"),
            option("B", "목장에서 먹이 주기나 승마 체험하기", List.of("ACTIVITY", "NATURE"), "WALK"))),
        new Question("SOUVENIR", "기념품으로 챙기고 싶은 건?", List.of(
            option("A", "그 지역 특산 먹거리", List.of("FOOD"), "MUST_EAT"),
            option("B", "직접 만든 공예품", List.of("CULTURE", "ACTIVITY"), "INDOOR"))));

    /**
     * 지금까지의 답(묻는 순서대로)으로 다음 질문을 정한다. 가장 최근에 고른 선택지의 이어지는 질문을 먼저 보고,
     * 없으면 그 앞 답들, 그래도 없으면 아직 묻지 않은 시작 질문 순서로 고른다.
     * 상관없음·건너뛰기는 고른 선택지가 없으므로 이어지는 질문을 만들지 않는다. 다 물었으면 null이다.
     */
    public static Question nextQuestion(SequencedMap<String, String> answers) {
        if (answers.size() >= MAX_QUESTIONS) return null;
        var candidates = new ArrayList<String>();
        for (var entry : answers.reversed().entrySet()) {
            var question = question(entry.getKey());
            if (question == null) continue;
            question.options().stream().filter(o -> o.id().equals(entry.getValue())).findFirst()
                .ifPresent(o -> candidates.addAll(o.next()));
        }
        candidates.addAll(START_IDS);
        QUESTIONS.forEach(q -> candidates.add(q.id()));
        return candidates.stream().filter(id -> !answers.containsKey(id)).findFirst().map(PreferenceDiscovery::question).orElse(null);
    }

    /**
     * 답변은 묻는 순서대로 질문 ID → 선택지 ID(A·B), 상관없음(ANY), 건너뛰기(SKIP)이다.
     * 없는 질문·선택지와, 앞선 답으로는 나올 수 없는 질문은 거부한다.
     * 선택을 바꾸면 이전 점수를 누적하지 않도록 매번 답변 전체로 다시 계산한다.
     */
    public static Result evaluate(SequencedMap<String, String> answers) {
        if (answers == null) answers = new LinkedHashMap<>();
        if (answers.size() > MAX_QUESTIONS) throw new IllegalArgumentException("Too many answers");
        var asked = new LinkedHashMap<String, String>();
        for (var entry : answers.entrySet()) {
            var question = question(entry.getKey());
            String value = entry.getValue();
            if (question == null || value == null || !(NO_PREFERENCE.equals(value) || SKIP.equals(value)
                    || question.options().stream().anyMatch(o -> o.id().equals(value))))
                throw new IllegalArgumentException("Unknown question or option");
            // 첫 질문은 시작 질문이어야 하고, 그 뒤는 앞선 답으로 정해지는 질문이어야 한다.
            boolean reachable = asked.isEmpty() ? START_IDS.contains(question.id()) : question.equals(nextQuestion(asked));
            if (!reachable) throw new IllegalArgumentException("Question out of order");
            asked.put(entry.getKey(), value);
        }
        Map<String, Integer> scores = new HashMap<>(); Map<String, List<String>> reasons = new HashMap<>();
        int answered = 0;
        for (var entry : answers.entrySet()) {
            if (SKIP.equals(entry.getValue())) continue;
            answered++;
            // 상관없음은 답한 것으로 세되 어떤 취향에도 점수를 주지 않는다. 고르지 않은 쪽을 비선호로 보지 않는다.
            if (NO_PREFERENCE.equals(entry.getValue())) continue;
            var option = question(entry.getKey()).options().stream().filter(o -> o.id().equals(entry.getValue())).findFirst().orElseThrow();
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
        // 건너뛴 질문과 아직 묻지 않은 질문은 모두 건너뛴 것으로 센다.
        return new Result(preferences, answered, MAX_QUESTIONS - answered);
    }

    /** 지난 답변 목록. 해당 질문으로 돌아가 답을 고칠 수 있게 순서를 함께 준다. */
    public static List<AnswerSummary> summarize(SequencedMap<String, String> answers) {
        var summaries = new ArrayList<AnswerSummary>();
        for (var entry : answers.entrySet()) {
            var question = question(entry.getKey());
            String choice = switch (entry.getValue()) {
                case NO_PREFERENCE -> "상관없음";
                case SKIP -> "건너뛰기";
                default -> question.options().stream().filter(o -> o.id().equals(entry.getValue())).findFirst().orElseThrow().scene();
            };
            summaries.add(new AnswerSummary(summaries.size(), question.prompt(), choice));
        }
        return summaries;
    }

    /** 바로 추천받을 때 날짜를 정하지 않았으므로, 오늘부터 7~30일 뒤 사이의 토요일 하나를 무작위로 고른다(토·일 1박 2일). */
    public static LocalDate pickWeekend(LocalDate today, RandomGenerator random) {
        var saturdays = saturdays(today.plusDays(7), today.plusDays(30));
        return saturdays.get(random.nextInt(saturdays.size()));
    }

    /**
     * 한 계절에만 즐길 수 있는 장면(스키·해수욕 등)이 여행 문장에 들어가면, 가까운 주말 대신 그 계절 성수기의 주말로 날짜를 잡는다.
     * 성수기 기간은 흔히 말하는 여름 휴가철(7월 말~8월 중순)과 스키 시즌 절정(12월 말~2월 초)을 기준으로 정했다.
     */
    public enum Season {
        SUMMER("여름", MonthDay.of(7, 20), MonthDay.of(8, 20)),
        WINTER("겨울", MonthDay.of(12, 20), MonthDay.of(2, 10));
        private final String label; private final MonthDay from, to;
        Season(String label, MonthDay from, MonthDay to) { this.label = label; this.from = from; this.to = to; }
        public String getLabel() { return label; }
        /** year에 시작하는 성수기의 마지막 날. 겨울은 해를 넘겨 끝난다. */
        private LocalDate end(int year) { return to.atYear(to.isBefore(from) ? year + 1 : year); }
        public boolean contains(LocalDate day) {
            return !day.isBefore(from.atYear(day.getYear() - 1)) && !day.isAfter(end(day.getYear() - 1))
                || !day.isBefore(from.atYear(day.getYear())) && !day.isAfter(end(day.getYear()));
        }
    }
    /** 그 계절에만 할 수 있는 장면. 여름·겨울을 고른 시작 질문의 장면도 계절이 정해져 있어 함께 둔다. */
    private static final Map<String, Season> SEASONAL_SCENES = Map.of(
        "여름, 시원한 물가에서 신나게 놀기", Season.SUMMER,
        "해수욕장에서 물놀이나 서핑하기", Season.SUMMER,
        "계곡 그늘에 발 담그고 쉬기", Season.SUMMER,
        "겨울, 찬 공기 속에서 따뜻하게 쉬기", Season.WINTER,
        "스키장이나 눈썰매장에서 신나게 놀기", Season.WINTER,
        "눈 쌓인 산사와 설경 명소를 천천히 걷기", Season.WINTER);

    /** 여행 문장에 들어갈 장면 중 먼저 나오는 계절 장면의 계절. 계절 장면이 없으면 null이다. */
    public static Season season(Result result) {
        return sentenceScenes(result).stream().map(SEASONAL_SCENES::get).filter(Objects::nonNull).findFirst().orElse(null);
    }

    /** 계절이 있으면 7일 뒤부터 가장 가까운 그 계절 성수기의 토요일 하나를, 없으면 7~30일 뒤 토요일 하나를 고른다. */
    public static LocalDate pickWeekend(LocalDate today, Season season, RandomGenerator random) {
        if (season == null) return pickWeekend(today, random);
        var earliest = today.plusDays(7);
        // 지난해에 시작해 아직 진행 중인 겨울 성수기부터 본다. 남은 토요일이 없으면 다음 성수기로 넘어간다.
        for (int year = earliest.getYear() - 1; ; year++) {
            var start = season.from.atYear(year);
            var saturdays = saturdays(start.isBefore(earliest) ? earliest : start, season.end(year));
            if (!saturdays.isEmpty()) return saturdays.get(random.nextInt(saturdays.size()));
        }
    }

    /** 세션에 둔 날짜를 계속 써도 되는지. 계절이 있으면 그 성수기 안, 없으면 7~30일 뒤여야 한다. */
    public static boolean weekendFits(LocalDate saturday, LocalDate today, Season season) {
        if (saturday.isBefore(today.plusDays(7))) return false;
        return season == null ? !saturday.isAfter(today.plusDays(30)) : season.contains(saturday);
    }

    private static List<LocalDate> saturdays(LocalDate from, LocalDate to) {
        if (to.isBefore(from)) return List.of();
        return from.datesUntil(to.plusDays(1)).filter(day -> day.getDayOfWeek() == DayOfWeek.SATURDAY).toList();
    }

    /** 여행 문장에 넣을 장면: 점수가 높은 취향 3개의 근거 장면 중 2개까지. */
    private static List<String> sentenceScenes(Result result) {
        // 시작 질문(노래·쉬는 날 같은 평소 모습)보다 이어지는 질문의 장면이 실제 여행 활동에 가까우므로 먼저 넣는다.
        var startScenes = START_IDS.stream().flatMap(id -> question(id).options().stream()).map(Option::scene).toList();
        return result.preferences().stream().limit(3).flatMap(p -> p.reasons().stream()).distinct()
            .sorted(Comparator.comparing(startScenes::contains)).limit(2).toList();
    }

    /**
     * 탐색 결과와 고른 주말로 AI에게 보낼 여행 문장을 만든다. 점수가 높은 취향 3개와 그 근거 장면 2개까지 넣는다.
     * 지역은 묻지 않았으므로 넣지 않는다(전국에서 추천). 사용자가 화면에서 고쳐서 보낼 수 있다.
     */
    public static String tripSentence(Result result, LocalDate saturday) {
        var sunday = saturday.plusDays(1);
        var sentence = new StringBuilder("%d년 %d월 %d일 토요일부터 %d월 %d일 일요일까지 1박 2일로 국내 여행을 가고 싶어요.".formatted(
            saturday.getYear(), saturday.getMonthValue(), saturday.getDayOfMonth(), sunday.getMonthValue(), sunday.getDayOfMonth()));
        var top = result.preferences().stream().limit(3).toList();
        if (!top.isEmpty()) {
            sentence.append(' ').append(String.join(", ", top.stream().map(Preference::name).toList()))
                .append(" 위주로 즐길 수 있는 곳이면 좋겠어요.");
            var scenes = sentenceScenes(result).stream().map(s -> "'" + s + "'").toList();
            sentence.append(" 특히 ").append(String.join(", ", scenes)).append(" 같은 여행이 끌려요.");
        }
        return sentence.toString();
    }

    public static Question question(String id) {
        return QUESTIONS.stream().filter(q -> q.id().equals(id)).findFirst().orElse(null);
    }
}
