package com.travel.travel_recommendation.service;

import lombok.extern.slf4j.Slf4j;

/**
 * OpenAI의 429 응답을 구분한다. 429는 '잠시 기다리면 풀리는 요청 제한'과 '크레딧 소진' 두 경우에 모두 온다.
 * 크레딧 소진은 다시 시도해도 풀리지 않으므로 사용자에게 다른 안내를 보여 준다.
 */
@Slf4j
final class OpenAiQuota {
    private OpenAiQuota() { }

    /** 429 응답 본문이 크레딧 소진(insufficient_quota)이면 true. 개발자가 원인을 알 수 있게 로그를 남기며, 인증키는 적지 않는다. */
    static boolean exhausted(String body) {
        boolean exhausted = body != null && body.contains("insufficient_quota");
        if (exhausted) log.warn("OpenAI 크레딧 소진(insufficient_quota). 서버가 쓰는 API 키(OPENAI_API_KEY 등)와 결제 상태를 확인하세요.");
        return exhausted;
    }
}
