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
public final class ReadableText {
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

    /** 분 단위 시간을 읽기 쉽게 바꾼다(예: 150 → "2시간 30분", 120 → "2시간", 45 → "45분"). */
    public static String duration(int minutes) {
        if (minutes < 60) return minutes + "분";
        return minutes / 60 + "시간" + (minutes % 60 == 0 ? "" : " " + minutes % 60 + "분");
    }

    /** 낱말의 받침에 맞는 조사를 고른다(예: "산책로" + "은" → "는"). 화면 템플릿에서도 쓴다. */
    public static String particle(String word, String particle) {
        if (particle == null) return "";
        // 이름 끝의 닫는 괄호·따옴표·공백은 소리가 없으므로 그 앞 글자로 받침을 본다(예: "38코스(바우길06구간)" → '간').
        int end = word.length() - 1;
        while (end > 0 && ")]'\"’” ".indexOf(word.charAt(end)) >= 0) end--;
        char last = word.isEmpty() ? ' ' : word.charAt(end);
        int coda = last >= '가' && last <= '힣' ? (last - '가') % 28 : 0;
        // 숫자는 읽는 소리로 본다. 1(일)·7(칠)·8(팔)은 ㄹ 받침(8), 0(영)·3(삼)·6(육)은 그 밖의 받침이 있다.
        if (last >= '0' && last <= '9') coda = "178".indexOf(last) >= 0 ? 8 : "036".indexOf(last) >= 0 ? 1 : 0;
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
