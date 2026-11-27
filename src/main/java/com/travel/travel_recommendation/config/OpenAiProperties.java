package com.travel.travel_recommendation.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

/**
 * OpenAI 연결 설정. reasoningEffort는 gpt-6 계열 추론 모델의 생각 깊이(none·low·medium…)이며 비우면 요청에 넣지 않는다.
 * apiKey는 처음 쓰는 키, extraApiKeys는 그 키의 크레딧이 소진됐을 때 차례로 써 볼 나머지 키다.
 */
@ConfigurationProperties("travel.openai")
public record OpenAiProperties(String apiKey, String model, String reasoningEffort, List<String> extraApiKeys) {
    @ConstructorBinding
    public OpenAiProperties { extraApiKeys = extraApiKeys == null ? List.of() : List.copyOf(extraApiKeys); }

    /** 키를 하나만 쓰는 경우(테스트 등). */
    public OpenAiProperties(String apiKey, String model, String reasoningEffort) { this(apiKey, model, reasoningEffort, List.of()); }

    public boolean isConfigured() { return apiKey != null && !apiKey.isBlank(); }

    /** 써 볼 순서대로 정리한 키 목록. 비어 있는 값과 같은 키가 두 번 들어온 것은 뺀다. */
    public List<String> apiKeys() {
        List<String> keys = new ArrayList<>();
        if (isConfigured()) keys.add(apiKey);
        for (String key : extraApiKeys)
            if (key != null && !key.isBlank() && !keys.contains(key.strip())) keys.add(key.strip());
        return keys;
    }

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
