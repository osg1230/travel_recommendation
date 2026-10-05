package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.TravelKeyword;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Component
public class OpenAiConditionClient {
    private final OpenAiProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final URI endpoint;
    private final Duration timeout;
    private final JsonNode schema;
    private final String instructions;

    @Autowired
    public OpenAiConditionClient(OpenAiProperties properties, ObjectMapper mapper) throws IOException {
        this(properties, mapper, URI.create("https://api.openai.com/v1/responses"), Duration.ofSeconds(25));
    }

    // 테스트에서만 로컬 응답 서버를 사용한다. 운영 API 주소를 사용자 입력으로 받지 않는다.
    OpenAiConditionClient(OpenAiProperties properties, ObjectMapper mapper, URI endpoint, Duration timeout) throws IOException {
        this.properties = properties;
        this.mapper = mapper;
        this.endpoint = endpoint;
        this.timeout = timeout;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        this.schema = mapper.readTree(new ClassPathResource("ai/condition-schema.json").getContentAsString(StandardCharsets.UTF_8));
        this.instructions = new ClassPathResource("ai/condition-instructions.txt").getContentAsString(StandardCharsets.UTF_8);
    }

    public JsonNode analyze(String text, Map<String, List<TravelKeyword>> choices) {
        if (!properties.isConfigured()) throw new ConditionAnalysisException("문장 분석 연결이 아직 준비되지 않았어요. 잠시 후 다시 이용해 주세요.");
        try {
            Map<String, List<Map<String, String>>> promptChoices = new LinkedHashMap<>();
            for (String field : List.of("season", "companion", "themes", "transportation")) {
                promptChoices.put(field, choices.getOrDefault(field, List.of()).stream()
                        .map(k -> Map.of("selectionValue", k.getSelectionValue(), "name", k.name())).toList());
            }
            Map<String, Object> input = Map.of("todayKst", LocalDate.now(ZoneId.of("Asia/Seoul")).toString(),
                    "choices", promptChoices, "travelText", text);
            Map<String, Object> body = properties.requestBase(); body.putAll(Map.of(
                    "instructions", instructions, "input", mapper.writeValueAsString(input),
                    "max_output_tokens", 1800, "text", Map.of("format", Map.of(
                            "type", "json_schema", "name", "travel_conditions", "strict", true, "schema", responseSchema(choices)))));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(timeout)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body), StandardCharsets.UTF_8)).build();
            // 유료 호출은 한 번만 수행한다. 자동 재시도 대신 사용자가 오류 안내 후 다시 요청한다.
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            // 429는 요청 제한과 크레딧 소진 두 경우다. 크레딧 소진은 기다려도 풀리지 않아 조건 고르기를 안내한다.
            if (response.statusCode() == 429) throw new ConditionAnalysisException(OpenAiQuota.exhausted(response.body())
                    ? "AI 이용 한도를 모두 써서 지금은 문장을 분석할 수 없어요. '조건 고르기'로 입력해 주세요."
                    : "문장 분석 사용량이 제한되었어요. 잠시 후 다시 시도해 주세요.");
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new ConditionAnalysisException("문장 분석 연결 설정을 확인해야 해요. 나중에 다시 이용해 주세요.");
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new ConditionAnalysisException("문장 분석 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
            if (response.body().length() > 64000) throw invalidResponse();
            JsonNode root = mapper.readTree(response.body());
            if (!"completed".equals(root.path("status").asText())) throw invalidResponse();
            String output = null;
            for (JsonNode item : root.path("output")) {
                if (!"message".equals(item.path("type").asText())) continue;
                for (JsonNode content : item.path("content")) {
                    if ("refusal".equals(content.path("type").asText()))
                        throw new ConditionAnalysisException("이 문장은 분석하기 어려워요. 여행 지역이나 기간을 중심으로 다시 적어 주세요.");
                    if ("output_text".equals(content.path("type").asText())) {
                        if (output != null || !content.path("text").isTextual()) throw invalidResponse();
                        output = content.path("text").asText();
                    }
                }
            }
            if (output == null || output.length() > 16000) throw invalidResponse();
            return mapper.readTree(output);
        } catch (ConditionAnalysisException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new ConditionAnalysisException("문장 분석 시간이 오래 걸려 중단했어요. 잠시 후 다시 시도해 주세요.");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ConditionAnalysisException("문장 분석이 중단됐어요. 다시 시도해 주세요.");
        } catch (IOException ex) {
            throw new ConditionAnalysisException("문장 분석 서비스에 연결하지 못했어요. 잠시 후 다시 시도해 주세요.");
        } catch (RuntimeException ex) {
            throw invalidResponse();
        }
    }

    private ConditionAnalysisException invalidResponse() {
        return new ConditionAnalysisException("분석 결과를 확인하지 못했어요. 문장을 구체적으로 적고 다시 시도해 주세요.");
    }

    private JsonNode responseSchema(Map<String, List<TravelKeyword>> choices) {
        ObjectNode current = (ObjectNode) schema.deepCopy();
        // 표시명·운영 코드와 DB ID를 혼동하지 않도록 출력 자체를 활성 ID 목록으로 제한한다.
        for (String field : List.of("season", "companion", "transportation")) {
            var values = ((ObjectNode) current.path("properties").path(field)).putArray("enum");
            values.addNull();
            choices.getOrDefault(field, List.of()).forEach(k -> values.add(k.getSelectionValue()));
        }
        ObjectNode themes = (ObjectNode) current.path("properties").path("themes");
        var themeChoices = choices.getOrDefault("themes", List.of());
        themes.put("maxItems", themeChoices.size());
        if (!themeChoices.isEmpty()) {
            var values = ((ObjectNode) themes.path("items")).putArray("enum");
            themeChoices.forEach(k -> values.add(k.getSelectionValue()));
        }
        return current;
    }
}
