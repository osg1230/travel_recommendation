package com.travel.travel_recommendation.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OpenAI 크레딧이 소진됐을 때 대신 쓰는 Claude 연결 설정. 키가 없으면 대체 호출을 하지 않는다.
 * effort는 생각 깊이(low·medium·high·xhigh·max)이며 비우면 low다.
 * primary가 true면 OpenAI를 거치지 않고 처음부터 Claude에 묻는다(두 AI를 비교 측정할 때 쓴다).
 */
@ConfigurationProperties("travel.claude")
public record ClaudeProperties(String apiKey, String model, int timeoutSeconds, boolean primary, String effort) {
    public ClaudeProperties { effort = effort == null || effort.isBlank() ? "low" : effort.strip(); }

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    // 설정 객체를 실수로 로그에 남겨도 인증키는 출력하지 않는다.
    @Override public String toString() { return "ClaudeProperties[model=" + model + ", configured=" + isConfigured() + "]"; }
}
