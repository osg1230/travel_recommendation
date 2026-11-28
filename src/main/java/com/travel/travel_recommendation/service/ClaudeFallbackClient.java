package com.travel.travel_recommendation.service;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.travel.travel_recommendation.config.ClaudeProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 크레딧이 소진됐을 때 같은 지시문·입력·출력 형식으로 Claude에 대신 묻는다.
 * 답은 OpenAI 응답과 같은 모양의 JSON으로 돌려줘, 각 클라이언트가 원래 하던 검증을 그대로 거친다.
 * 웹 검색을 쓰는 가격 검색·장소 확인은 응답 모양이 달라 여기서 다루지 않는다.
 */
@Slf4j
@Component
public class ClaudeFallbackClient {
    // Claude는 생각(thinking)에도 출력 토큰을 쓰므로 OpenAI 요청의 상한보다 넉넉하게 둔다.
    private static final long MAX_TOKENS = 16000;
    // Claude의 구조화 출력이 받지 않는 길이·개수 제한. 빼고 보내도 각 클라이언트가 응답을 다시 검사한다.
    private static final Set<String> UNSUPPORTED = Set.of("minLength", "maxLength", "minItems", "maxItems", "minimum", "maximum", "multipleOf");
    private final ClaudeProperties properties;
    private final ObjectMapper mapper;
    private final AnthropicClient client;
    // true면 OpenAI를 거치지 않고 처음부터 Claude에 묻는다. 비교 측정에서 실행 중에 바꿀 수 있게 설정값을 복사해 둔다.
    private volatile boolean primary;
    // Claude가 대신 답한 횟수. 비교 측정에서 어느 AI가 답했는지 확인할 때 쓴다.
    private final java.util.concurrent.atomic.AtomicInteger answers = new java.util.concurrent.atomic.AtomicInteger();

    @Autowired
    public ClaudeFallbackClient(ClaudeProperties properties, ObjectMapper mapper) {
        this(properties, mapper, null);
    }

    // API 주소는 고정하고 테스트에서만 로컬 응답 서버를 주입한다.
    ClaudeFallbackClient(ClaudeProperties properties, ObjectMapper mapper, String baseUrl) {
        this.properties = properties;
        this.mapper = mapper;
        this.primary = properties.primary();
        if (!properties.isConfigured()) { client = null; return; }
        // 유료 호출이라 자동 재시도는 하지 않는다.
        var builder = AnthropicOkHttpClient.builder().apiKey(properties.apiKey())
            .timeout(Duration.ofSeconds(properties.timeoutSeconds())).maxRetries(0);
        if (baseUrl != null) builder.baseUrl(baseUrl);
        client = builder.build();
    }

    public boolean configured() { return client != null; }
    public boolean primary() { return client != null && primary; }
    public void usePrimary(boolean primary) { this.primary = primary; }
    public int answerCount() { return answers.get(); }

    /**
     * OpenAI 요청 본문(instructions·input·text.format.schema)으로 Claude에 묻고 OpenAI 응답 모양의 JSON을 돌려준다.
     * 실패하면 null을 돌려줘 호출한 쪽이 기존 한도 안내를 그대로 보여 주게 한다.
     */
    String answer(Map<String, Object> openAiBody) {
        try {
            Object schema = ((Map<?, ?>) ((Map<?, ?>) openAiBody.get("text")).get("format")).get("schema");
            var format = JsonOutputFormat.Schema.builder();
            // 스키마가 Map이든 JsonNode든 일반 Map으로 바꾼 뒤 Claude가 받지 않는 제한을 뺀다.
            ((Map<?, ?>) supported(mapper.convertValue(schema, Map.class)))
                .forEach((key, value) -> format.putAdditionalProperty(String.valueOf(key), JsonValue.from(value)));
            MessageCreateParams params = MessageCreateParams.builder()
                .model(properties.model())
                .maxTokens(MAX_TOKENS)
                .system(String.valueOf(openAiBody.get("instructions")))
                .addUserMessage(String.valueOf(openAiBody.get("input")))
                // 구조화 출력만 받으므로 기본은 생각 깊이를 낮춰(low) 응답 시간을 줄인다. travel.claude.effort로 바꿀 수 있다.
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.of(properties.effort()))
                    .format(JsonOutputFormat.builder().schema(format.build()).build()).build())
                .build();
            Message message = client.messages().create(params);
            answers.incrementAndGet();
            log.info("Claude({})가 OpenAI 대신 응답했습니다.", properties.model());
            return mapper.writeValueAsString(openAiShape(message));
        } catch (RuntimeException e) {
            // 원인 파악용으로 예외 종류와 메시지만 남긴다. 인증키와 요청 내용은 적지 않는다.
            log.warn("Claude 대체 호출 실패: {} {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** Claude 응답을 OpenAI Responses 응답 모양(status, output[].content[])으로 옮긴다. */
    private Map<String, Object> openAiShape(Message message) {
        StopReason stop = message.stopReason().orElse(null);
        if (StopReason.REFUSAL.equals(stop))
            return completed(Map.of("type", "refusal", "refusal", "Claude가 응답을 거절했습니다."));
        // 출력 토큰이 모자라 중간에 끊긴 답은 완성된 답으로 넘기지 않는다.
        if (!StopReason.END_TURN.equals(stop)) return Map.of("status", "incomplete");
        String text = message.content().stream().flatMap(block -> block.text().stream())
            .map(block -> block.text()).collect(Collectors.joining());
        return completed(Map.of("type", "output_text", "text", text));
    }

    private static Map<String, Object> completed(Map<String, Object> content) {
        return Map.of("status", "completed", "output", List.of(Map.of("type", "message", "content", List.of(content))));
    }

    /** 스키마를 따라 내려가며 Claude가 받지 않는 제한을 뺀다. properties 아래의 키는 항목 이름이므로 지우지 않는다. */
    static Object supported(Object schema) {
        if (schema instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(item -> copy.add(supported(item)));
            return copy;
        }
        if (!(schema instanceof Map<?, ?> map)) return schema;
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (UNSUPPORTED.contains(key)) return;
            if ("properties".equals(key) && value instanceof Map<?, ?> fields) {
                Map<String, Object> kept = new LinkedHashMap<>();
                fields.forEach((name, field) -> kept.put(String.valueOf(name), supported(field)));
                copy.put("properties", kept);
            } else copy.put(String.valueOf(key), supported(value));
        });
        // Claude는 enum 안의 null을 받지 않는다. '목록 중 하나 또는 null'은 anyOf(목록 | null)로 바꿔 같은 뜻을 유지한다.
        if (copy.get("enum") instanceof List<?> values && values.contains(null)) {
            Map<String, Object> choice = new LinkedHashMap<>();
            Object type = copy.remove("type");
            if (type instanceof List<?> types) type = types.stream().map(String::valueOf).filter(t -> !"null".equals(t)).findFirst().orElse("string");
            if (type != null) choice.put("type", type);
            choice.put("enum", values.stream().filter(java.util.Objects::nonNull).toList());
            copy.remove("enum");
            copy.put("anyOf", List.of(choice, Map.of("type", "null")));
        }
        return copy;
    }
}
