package com.travel.travel_recommendation.service;

import java.util.Arrays;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 추천 이유에서 "확인이 필요합니다"처럼 확인을 미루는 말을 찾고 뺀다.
 * 여행지별 근거를 주고도 이런 말이 나오면 사용자는 그 여행을 할 수 있는지 알 수 없으므로 이유에 남기지 않는다.
 * 운영·요금·전시 내용처럼 확인하지 않은 점은 추천 화면 아래에 한 번 안내한다.
 */
final class ReasonHedges {
    static final Pattern PATTERN = Pattern.compile(
        "확인이 필요|확인하지 못|확인되지 않|확인이 어려|확인하기 어려|확인할 수 없|알 수 없|찾지 못|확실하지 않|보장할 수 없|방문 전 확인|확인해 보|확인해 주");
    private ReasonHedges() {}

    static boolean found(String reason) {
        return reason != null && PATTERN.matcher(reason).find();
    }

    /** 확인을 미루는 문장을 뺀다. 문장은 마침표·물음표·느낌표 뒤 공백에서 나눈다. 모든 문장이 빠지면 빈 문자열이다. */
    static String without(String reason) {
        return Arrays.stream(reason.strip().split("(?<=[.!?])\\s+")).filter(sentence -> !found(sentence))
            .collect(Collectors.joining(" "));
    }
}
