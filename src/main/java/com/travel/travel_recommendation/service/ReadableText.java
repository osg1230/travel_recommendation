package com.travel.travel_recommendation.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GPT가 지시를 어기고 사용자에게 보이는 글에 입력 필드 이름(예: purposePlaces)을 쓰면 화면에 보일 말로 바꾼다.
 * 필드 이름 뒤의 조사도 함께 잡아 바꾼 말의 받침에 맞춘다(예: "travelPurpose와" → "여행 목적과").
 * 다른 단어의 일부(예: THEMES)는 바꾸지 않는다.
 */
final class ReadableText {
    private final Map<Pattern, String> terms;

    /** names는 필드 이름 → 화면에 보일 말이다. */
    ReadableText(Map<String, String> names) {
        Map<Pattern, String> patterns = new LinkedHashMap<>();
        names.forEach((name, label) -> patterns.put(
            Pattern.compile("`?\\b" + Pattern.quote(name) + "\\b`?(?:(으로|와|과|은|는|을|를|이|가|로)(?![가-힣]))?"), label));
        this.terms = Map.copyOf(patterns);
    }

    String apply(String text) {
        for (var e : terms.entrySet()) {
            String label = e.getValue();
            text = e.getKey().matcher(text).replaceAll(m -> Matcher.quoteReplacement(label + particle(label, m.group(1))));
        }
        return text;
    }

    /** 장소 이름 뒤에 붙은 내부 ID 괄호를 뺀다(예: "송정해변(770)" → "송정해변"). ids에 있는 번호만 지우고 다른 숫자는 그대로 둔다. */
    static String withoutIds(String text, java.util.Collection<Long> ids) {
        for (Long id : ids)
            text = text.replaceAll("\\s*\\(\\s*(?:placeId\\s*[:=]?\\s*)?" + id + "\\s*\\)|\\s*\\bplaceId\\s*[:=]?\\s*" + id + "\\b", "");
        return text;
    }

    static String particle(String word, String particle) {
        if (particle == null) return "";
        char last = word.charAt(word.length() - 1);
        int coda = last >= '가' && last <= '힣' ? (last - '가') % 28 : 0;
        return switch (particle) {
            case "와", "과" -> coda != 0 ? "과" : "와";
            case "은", "는" -> coda != 0 ? "은" : "는";
            case "을", "를" -> coda != 0 ? "을" : "를";
            case "이", "가" -> coda != 0 ? "이" : "가";
            case "으로", "로" -> coda != 0 && coda != 8 ? "으로" : "로";
            default -> particle;
        };
    }
}
