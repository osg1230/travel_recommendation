package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.Instant;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** 서버가 만든 일정 초안에 AI 보조 안내를 붙인다. 보조 기능이므로 실패하면 초안을 그대로 제공한다. */
@Component
@RequiredArgsConstructor
public class ItineraryAssistant {
    private final OpenAiItineraryClient client;
    // 형식이 맞지 않으면 그 이유를 전달해 다시 요청한다. 요구사항의 자기수정 2회 제한을 따른다.
    static final int MAX_RETRIES=2;
    private static final int BUDGET_SECONDS=45;

    /** jobDeadline은 일정 작업 전체의 제한 시각이다. 보조 때문에 작업이 시간 초과로 실패하지 않게 한다. */
    public Result assist(Input input,Result plan,Instant jobDeadline) { return assist(input,plan,jobDeadline,Map.of()); }

    /** overviews는 장소 ID → 관광공사 소개문이다. AI가 관광지마다 추천 활동을 쓸 근거로 함께 보낸다. */
    public Result assist(Input input,Result plan,Instant jobDeadline,Map<Long,String> overviews) {
        if(!client.configured()) return plan;
        Instant budget=Instant.now().plusSeconds(BUDGET_SECONDS);
        Instant deadline=jobDeadline.isBefore(budget)?jobDeadline:budget;
        String previousFailure=null;
        for(int attempt=0;attempt<=MAX_RETRIES;attempt++) {
            // 일정 작업 전체에 시간 제한이 있으므로 남은 시간이 없으면 재요청하지 않는다.
            if(Instant.now().isAfter(deadline)) break;
            try {
                var assist=client.assist(input.conditions(),plan,previousFailure,overviews);
                // AI가 쓴 관광지별 추천 활동을 일정 항목 메모에 'AI 추천 활동' 줄로 더한다. 시각·장소·순서는 바꾸지 않는다.
                return PlaceReasons.apply(plan,assist.placeReasons()).withAssist(assist);
            } catch(RecommendationException error) {
                // 형식 문제만 다시 묻는다. 설정·인증·한도·연결 문제는 다시 불러도 같은 결과다.
                if(!"AI_INVALID_RESPONSE".equals(error.getCode())) return plan;
                previousFailure=error.getMessage();
            } catch(RuntimeException error) { return plan; }
        }
        return plan;
    }
}
