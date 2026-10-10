package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.*;

/**
 * 일정의 중심으로 삼을 관광지를 고른다. 근거는 두 가지다.
 * ① GPT가 추천 이유와 함께 적은 장소 이름(추천 결과의 centerPlaces) ② 사람이 작성한 여행지 소개 문구의 단어(예: "한옥마을").
 * 이름이 실제 후보와 맞는 곳만 쓴다. 일정 화면에는 "일정의 중심으로 고른 곳"으로 보여 준다.
 * (추천 결과 카드에는 GPT가 적은 이름 그대로를 '대표 코스'로 보여 주되 AI가 꼽은 순서라고 안내한다, 2026-10-10.)
 */
public final class CenterPlaces {
    private CenterPlaces() {}

    /** 소개 문구에서 장소 이름으로 볼 수 없는 일반 단어. */
    private static final Set<String> COMMON_WORDS = Set.of("지역", "여행", "정보", "음식", "지역음식", "해산물", "함께", "전통", "즐길",
        "있는", "살펴볼", "둘러볼", "걸으며", "관광지", "해양", "도심", "명소", "자연", "문화");
    /** 단어 끝에 붙는 조사. 긴 것부터 뗀다. */
    private static final List<String> PARTICLES = List.of("에서", "으로", "까지", "과", "와", "을", "를", "의", "이", "가", "은", "는", "도", "에", "로");

    /** 여행지 소개 문구에서 장소 이름과 맞춰 볼 단어(두 글자 이상, 일반 단어 제외). 문구에 나온 순서를 지킨다. */
    static List<String> descriptionWords(String description) {
        if (description == null) return List.of();
        var words = new ArrayList<String>();
        for (String raw : description.split("[\\s,·.]+")) {
            String word = raw.replaceAll("[^가-힣A-Za-z0-9]", "");
            for (String particle : PARTICLES)
                if (word.length() > particle.length() + 1 && word.endsWith(particle)) { word = word.substring(0, word.length() - particle.length()); break; }
            if (word.length() >= 2 && !word.endsWith("니다") && !COMMON_WORDS.contains(word) && !words.contains(word)) words.add(word);
        }
        return words;
    }

    /**
     * 후보(pool) 중 일정의 중심으로 삼을 곳의 ID를 limit개까지 고른다. GPT가 적은 이름을 먼저, 소개 문구 단어를 다음으로 본다.
     * 한 이름·단어에 여러 곳이 맞으면 후보 순서대로 한 곳만 쓴다. 맞는 곳이 없으면 비어 있다.
     */
    public static List<Long> pick(List<PlaceCandidate> pool, String destinationName, String description,
                                  List<String> suggested, int limit) {
        var picked = new ArrayList<Long>();
        if (suggested != null)
            for (String name : suggested) {
                if (picked.size() >= limit) break;
                matches(pool, key(name, destinationName), destinationName).stream()
                    .filter(p -> !picked.contains(p.placeId())).findFirst().ifPresent(p -> picked.add(p.placeId()));
            }
        // 소개 문구 단어는 후보 한 곳에만 맞을 때만 쓴다. "마을"·"서원"처럼 여러 곳에 맞는 단어는 엉뚱한 곳을 고르기 쉽다.
        for (String word : descriptionWords(description)) {
            if (picked.size() >= limit) break;
            var found = matches(pool, key(word, destinationName), destinationName);
            if (found.size() == 1 && !picked.contains(found.getFirst().placeId())) picked.add(found.getFirst().placeId());
        }
        return List.copyOf(picked);
    }

    /**
     * 목적 시설 중 중심 장소와 같은 종류인 곳을 뺀다. 예) 목적 "전통 마을이랑 서원"에서 중심 장소로 하회마을·도산서원을 골랐으면
     * 가송농촌전통테마마을·경광서원은 필수로 미리 고르지 않는다. 중심 장소가 이미 그 목적을 채우고, 덜 알려진 곳까지 필수로 두면
     * 동선이 넓어지기 때문이다. 종류는 분류(예: "서원")나 이름 끝 두 글자(예: "마을")가 같으면 같다고 본다.
     * 카카오 분류가 "관광단지"·"체험마을"처럼 달라도 이름으로 맞추기 위해서다.
     */
    public static List<Long> notCoveredByCenters(List<PlaceCandidate> pool, List<Long> purposeIds, List<Long> centerIds) {
        var centers = pool.stream().filter(p -> centerIds.contains(p.placeId())).toList();
        return purposeIds.stream().filter(id -> pool.stream().filter(p -> p.placeId().equals(id)).findFirst()
            .map(purpose -> centers.stream().noneMatch(center -> sameKind(purpose, center))).orElse(true)).toList();
    }

    private static boolean sameKind(PlaceCandidate a, PlaceCandidate b) {
        if (a.placeId().equals(b.placeId())) return true;
        // "관광,명소"는 분류가 더 나뉘지 않은 곳에 붙는 넓은 분류라 종류로 보지 않는다.
        String kind = TripConstraints.kind(a);
        if (!kind.isEmpty() && !kind.equals("관광,명소") && kind.equals(TripConstraints.kind(b))) return true;
        String nameA = key(a.name(), null), nameB = key(b.name(), null);
        return nameA.length() >= 3 && nameB.length() >= 3 && nameA.endsWith(nameB.substring(nameB.length() - 2));
    }

    /** 이름이 key를 포함하거나 key에 포함되는 후보. 두 글자 미만은 맞추지 않는다. */
    private static List<PlaceCandidate> matches(List<PlaceCandidate> pool, String key, String destinationName) {
        if (key.length() < 2) return List.of();
        return pool.stream().filter(p -> { String name = key(p.name(), destinationName);
            return name.length() >= 2 && (name.contains(key) || key.contains(name)); }).toList();
    }

    /** 비교용 이름: 기호·공백을 빼고, 앞에 붙은 여행지 이름(예: "전주한옥마을"의 "전주")을 뗀다. */
    private static String key(String name, String destinationName) {
        String compact = name == null ? "" : name.replaceAll("[^가-힣A-Za-z0-9]", "");
        String city = destinationName == null ? "" : destinationName.replaceAll("[^가-힣A-Za-z0-9]", "");
        return !city.isEmpty() && compact.startsWith(city) && compact.length() > city.length() + 1 ? compact.substring(city.length()) : compact;
    }
}
