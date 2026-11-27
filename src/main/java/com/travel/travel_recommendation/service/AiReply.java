package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.config.OpenAiProperties;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/** AI 호출 결과의 상태 코드와 본문. OpenAI가 답했든 Claude가 대신 답했든 같은 모양으로 다룬다. requestId는 OpenAI 오류 로그용이다. */
record AiReply(int statusCode, String body, String requestId) {

    /**
     * OpenAI에 요청을 보낸다. 크레딧 소진(429 insufficient_quota)이면 설정된 다음 OpenAI 키로 같은 요청을 다시 보내고,
     * 모든 키가 소진됐을 때만 Claude에 대신 묻는다(claude가 null이면 묻지 않는다).
     * Claude도 실패하면 마지막 OpenAI 429 응답을 그대로 돌려줘 기존 한도 안내가 나온다.
     */
    static AiReply send(HttpClient http, HttpRequest request, Map<String, Object> body, OpenAiProperties properties,
                        ClaudeFallbackClient claude) throws IOException, InterruptedException {
        // Claude를 먼저 쓰도록 설정했으면(travel.claude.primary) OpenAI에 보내지 않는다. 실패해도 OpenAI로 넘어가지 않고 연결 실패로 알린다.
        if (claude != null && claude.primary()) {
            String answer = claude.answer(body);
            return answer != null ? new AiReply(200, answer, "") : new AiReply(503, "", "");
        }
        List<String> keys = properties.apiKeys();
        // request는 첫 번째 키로 만들어져 있다. 두 번째 키부터는 인증 헤더만 바꿔 다시 보낸다.
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        for (int i = 1; i < keys.size() && exhausted(response); i++)
            response = http.send(withKey(request, keys.get(i)), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (exhausted(response) && claude != null && claude.configured()) {
            String answer = claude.answer(body);
            if (answer != null) return new AiReply(200, answer, "");
        }
        return new AiReply(response.statusCode(), response.body(), response.headers().firstValue("x-request-id").orElse(""));
    }

    private static boolean exhausted(HttpResponse<String> response) {
        return response.statusCode() == 429 && OpenAiQuota.exhausted(response.body());
    }

    /** 같은 요청에서 인증 헤더만 다른 키로 바꾼다. */
    private static HttpRequest withKey(HttpRequest request, String key) {
        return HttpRequest.newBuilder(request, (name, value) -> !name.equalsIgnoreCase("Authorization"))
            .header("Authorization", "Bearer " + key).build();
    }
}
