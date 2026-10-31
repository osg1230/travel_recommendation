package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ItineraryAssistantTests {
    private final OpenAiItineraryClient client=mock(OpenAiItineraryClient.class);
    private final ItineraryAssistant assistant=new ItineraryAssistant(client);
    private final Assist assist=new Assist("하루 일정 안내",List.of(new DayAdvice(1,"편한 신발을 준비해 주세요.")),Instant.now());

    private Input input() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",LocalDate.of(2026,10,1),LocalDate.of(2026,10,1),1,null,2,false,false,List.of(),"");
        var destination=new DestinationCandidate(99L,"강릉","강원","소개","https://www.gn.go.kr/",LocalDateTime.of(2026,9,1,0,0));
        var pool=new PlaceCandidateView(UUID.randomUUID(),destination,1,false,List.of(),List.of(),List.of());
        return new Input(UUID.randomUUID(),pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
    }
    private Result plan() {
        var item=new Item("VISIT",10L,"오죽헌",LocalTime.of(9,0),LocalTime.of(10,30),"관광·관람","","",null,null);
        return new Result("강릉",List.of(new Day(1,LocalDate.of(2026,10,1),List.of(item))),KakaoMapClient.Transport.CAR,Instant.now());
    }
    private RecommendationException invalid() { return new RecommendationException("AI_INVALID_RESPONSE","응답 형식이 달라요."); }
    private Instant later() { return Instant.now().plusSeconds(60); }

    // 일정 배치가 오래 걸려 남은 시간이 없으면 보조를 시도하지 않는다.
    @Test void skipsAssistWhenTheJobDeadlineHasPassed() {
        when(client.configured()).thenReturn(true);
        var plan=plan();
        assertThat(assistant.assist(input(),plan,Instant.now().minusSeconds(1))).isSameAs(plan);
        verify(client,never()).assist(any(),any(),any(),any());
    }

    @Test void skipsAssistWithoutCallingWhenNotConfigured() {
        when(client.configured()).thenReturn(false);
        var plan=plan();
        assertThat(assistant.assist(input(),plan,later())).isSameAs(plan);
        verify(client,never()).assist(any(),any(),any(),any());
    }
    // 형식 문제는 이유를 전달해 다시 묻되 자기수정은 2회까지만 한다.
    @Test void retriesFormatFailuresWithTheReasonUpToTwiceAndThenKeepsTheDraft() {
        when(client.configured()).thenReturn(true);
        doThrow(invalid()).doThrow(invalid()).doReturn(assist).when(client).assist(any(),any(),any(),any());
        assertThat(assistant.assist(input(),plan(),later()).assist()).isEqualTo(assist);
        verify(client,times(3)).assist(any(),any(),any(),any());
        verify(client).assist(any(),any(),isNull(),any());
        verify(client,times(2)).assist(any(),any(),eq("응답 형식이 달라요."),any());

        clearInvocations(client);
        doThrow(invalid()).when(client).assist(any(),any(),any(),any());
        var plan=plan();
        assertThat(assistant.assist(input(),plan,later())).isSameAs(plan);
        verify(client,times(ItineraryAssistant.MAX_RETRIES+1)).assist(any(),any(),any(),any());
    }
    @Test void doesNotRetryConnectionQuotaOrUnexpectedFailures() {
        when(client.configured()).thenReturn(true);
        for(var error:new RuntimeException[]{new RecommendationException("AI_AUTH","설정 확인"),
                new RecommendationException("AI_RATE_LIMIT","사용량 제한"),new IllegalStateException("예상 밖 오류")}) {
            clearInvocations(client);
            doThrow(error).when(client).assist(any(),any(),any(),any());
            var plan=plan();
            // 보조 기능이므로 실패를 일정 생성 실패로 올리지 않는다.
            assertThat(assistant.assist(input(),plan,later())).isSameAs(plan);
            verify(client,times(1)).assist(any(),any(),any(),any());
        }
    }
    @Test void keepsScheduleFieldsAndOldResultJsonWithoutAssist() {
        when(client.configured()).thenReturn(true);
        doReturn(assist).when(client).assist(any(),any(),any(),any());
        var plan=plan();
        var result=assistant.assist(input(),plan,later());
        assertThat(result.days()).isEqualTo(plan.days());
        assertThat(result.destination()).isEqualTo(plan.destination());
        assertThat(result.transport()).isEqualTo(plan.transport());
        assertThat(result.createdAt()).isEqualTo(plan.createdAt());
        // 보조 안내를 넣기 전에 저장한 작업 JSON에는 assist가 없어도 그대로 복원된다.
        var mapper=new tools.jackson.databind.json.JsonMapper();
        var tree=mapper.valueToTree(plan);
        ((tools.jackson.databind.node.ObjectNode)tree).remove("assist");
        assertThat(mapper.treeToValue(tree,Result.class).assist()).isNull();
    }
}
