package com.travel.travel_recommendation.config;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAI 연결 설정. reasoningEffort는 gpt-6 계열 추론 모델의 생각 깊이(none·low·medium…)이며 비우면 요청에 넣지 않는다. */
@ConfigurationProperties("travel.openai")
public record OpenAiProperties(String apiKey, String model, String reasoningEffort) {
    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    /**
     * 모든 OpenAI 요청에 공통으로 들어가는 항목(model·store·reasoning). 각 클라이언트는 여기에 지시문·입력·출력 형식을 더한다.
     * 추론 모델은 기본 effort가 medium이라 응답이 느리고 출력 토큰 상한을 생각에 먼저 쓰므로, 구조화 출력만 받는 이 서비스는 none을 기본으로 둔다.
     * gpt-4.1처럼 추론이 없는 모델은 reasoning 항목을 거부하므로 그때는 OPENAI_REASONING_EFFORT를 비운다.
     */
    public Map<String, Object> requestBase() {
        Map<String, Object> base = new LinkedHashMap<>();
        base.put("model", model);
        base.put("store", false);
        if (reasoningEffort != null && !reasoningEffort.isBlank()) base.put("reasoning", Map.of("effort", reasoningEffort));
        return base;
    }

    // 설정 객체를 실수로 로그에 남겨도 인증키는 출력하지 않는다.
    @Override public String toString() { return "OpenAiProperties[model=" + model + ", configured=" + isConfigured() + "]"; }
}
