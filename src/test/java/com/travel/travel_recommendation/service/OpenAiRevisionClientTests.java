package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.service.OpenAiRevisionClient.Candidate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiRevisionClientTests {
    private final JsonMapper mapper=new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status=new AtomicInteger(200);
    private final AtomicReference<String> response=new AtomicReference<>(),request=new AtomicReference<>();
    private final List<Candidate> current=List.of(new Candidate(1L,"오죽헌","박물관","소개"),new Candidate(2L,"경포대","누각","소개"));
    private final List<Candidate> spare=List.of(new Candidate(3L,"안목해변","해변","바다"),new Candidate(4L,"커피거리","거리","카페"));

    private ConfirmedTravelConditions conditions() {
        return new ConfirmedTravelConditions("TEXT","private-original-input","강릉",LocalDate.of(2026,10,1),
            LocalDate.of(2026,10,1),1,null,2,false,null,List.of(),"private-summary");
    }
    private Input input() {
        return new Input(UUID.randomUUID(),null,conditions(),LocalTime.of(9,0),LocalTime.of(18,0),KakaoMapClient.Transport.CAR,90);
    }
    private Result plan() {
        var item=new Item("VISIT",1L,"오죽헌",LocalTime.of(9,0),LocalTime.of(10,30),"관광","https://map.kakao.com/","https://www.gn.go.kr/",null,null);
        return new Result("강릉",List.of(new Day(1,LocalDate.of(2026,10,1),List.of(item))),KakaoMapClient.Transport.CAR,Instant.now());
    }
    @BeforeEach void setup() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{
            request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(status.get(),bytes.length); e.getResponseBody().write(bytes); e.close();
        });
        server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiRevisionClient client(String key) {
        return new OpenAiRevisionClient(new OpenAiProperties(key,"gpt-4.1-mini"),mapper,
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),Duration.ofSeconds(3));
    }
    private void output(String json) {
        // 후보에 없는 장소 칸이 생기기 전의 응답 예시는 빈 값으로 채워 새 형식에 맞춘다.
        if(json.startsWith("{\"action\"") && !json.contains("unavailablePlaces"))
            json=json.substring(0,json.length()-1)+",\"unavailablePlaces\":[],\"exactPlace\":false}";
        // 집 출발·귀가 시각 칸이 생기기 전의 응답 예시도 null로 채운다.
        if(json.startsWith("{\"action\"") && !json.contains("departAt"))
            json=json.substring(0,json.length()-1)+",\"departAt\":null,\"arriveBy\":null}";
        if(json.startsWith("{\"action\"") && !json.contains("firstArrival"))
            json=json.substring(0,json.length()-1)+",\"firstArrival\":null}";
        // 이동수단·식당·숙소 칸이 생기기 전의 응답 예시도 "바꾸지 않음"으로 채운다.
        if(json.startsWith("{\"action\"") && !json.contains("stayPlaceId"))
            json=json.substring(0,json.length()-1)+",\"transport\":null,\"avoidRestaurants\":[],\"preferRestaurants\":[],\"stayPlaceId\":null}";
        if(json.startsWith("{\"action\"") && !json.contains("homeMinutes"))
            json=json.substring(0,json.length()-1)+",\"homeMinutes\":null,\"returnMinutes\":null}";
        response.set(mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
            List.of(Map.of("type","output_text","text",json)))))));
    }
    private OpenAiRevisionClient.Proposal call(Set<Long> fixed) {
        return client("test-key").propose(conditions(),input(),plan(),current,spare,fixed,"박물관 빼고 바다로 바꿔 줘",null);
    }

    // 첫날 도착을 늦추는 요청은 하루 시작(dayStart)이 아니라 집 출발 시각(departAt)으로 받는다. 이 칸은 08~21시 제한을 받지 않는다.
    @Test void parsesHomeDepartureAndArrivalTimes() {
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"첫날 집 출발을 7시로 앞당겼어요.\",\"unavailablePlaces\":[],\"exactPlace\":false,\"departAt\":\"07:00\",\"arriveBy\":\"22:30\",\"firstArrival\":\"11:00\"}");
        var proposal=call(Set.of());
        assertThat(proposal.departAt()).isEqualTo(LocalTime.of(7,0));
        assertThat(proposal.arriveBy()).isEqualTo(LocalTime.of(22,30));
        assertThat(proposal.firstArrival()).isEqualTo(LocalTime.of(11,0));
        assertThat(request.get()).contains("departAt").contains("점심부터");
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"설명\",\"unavailablePlaces\":[],\"exactPlace\":false,\"departAt\":\"7시\",\"arriveBy\":null}");
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,
            e->assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
    }

    @Test void parsesRevisionWithinGivenCandidatesAndSendsOnlyNeededData() {
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":\"10:00\",\"end\":null,\"visitMinutes\":null,\"explanation\":\"박물관 대신 바다가 보이는 안목해변으로 바꿨어요.\"}");
        var proposal=call(Set.of());
        assertThat(proposal.clarify()).isFalse();
        assertThat(proposal.replace()).containsExactly(1L);
        assertThat(proposal.add()).containsExactly(3L);
        assertThat(proposal.start()).isEqualTo(LocalTime.of(10,0));
        assertThat(proposal.end()).isNull();
        assertThat(proposal.visitMinutes()).isNull();
        assertThat(request.get()).contains("박물관 빼고 바다로 바꿔 줘").contains("spareCandidates")
            .doesNotContain("private-original-input").doesNotContain("private-summary");
    }

    @Test void clarifyReturnsQuestionWithoutChanges() {
        output("{\"action\":\"CLARIFY\",\"question\":\"어느 날의 관광지를 바꿀까요?\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"\"}");
        var proposal=call(Set.of());
        assertThat(proposal.clarify()).isTrue();
        assertThat(proposal.question()).isEqualTo("어느 날의 관광지를 바꿀까요?");
    }

    // 식사·숙소·날짜처럼 할 수 없는 요청은 GPT 문장 대신 서버의 고정 안내를 쓴다.
    @Test void unsupportedRequestUsesFixedNoticeWithoutChanges() {
        output("{\"action\":\"UNSUPPORTED\",\"question\":\"어떤 식당으로 바꿀까요?\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"\"}");
        var proposal=call(Set.of());
        assertThat(proposal.unsupported()).isTrue(); assertThat(proposal.clarify()).isFalse();
        assertThat(proposal.question()).isEqualTo(OpenAiRevisionClient.UNSUPPORTED_MESSAGE).doesNotContain("어떤 식당");
        assertThat(proposal.replace()).isEmpty();
        var instructions=mapper.readTree(request.get()).path("instructions").asText();
        assertThat(instructions).contains("UNSUPPORTED","임의로 고르지 말고","placeId 숫자나");
        output("{\"action\":\"UNSUPPORTED\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"\"}");
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,
            e->assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
    }

    // 사용자에게 보이는 설명·질문에서 내부 장소 ID와 입력 필드 이름을 뺀다.
    @Test void explanationAndQuestionHideInternalIdsAndFieldNames() {
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"오죽헌(1)을 spareCandidates의 안목해변(placeId: 3)으로 바꾸고 경포대 placeId 2는 유지했어요. 10시(2곳)는 그대로예요.\"}");
        assertThat(call(Set.of()).explanation())
            .isEqualTo("오죽헌을 교체 후보의 안목해변으로 바꾸고 경포대는 유지했어요. 10시(2곳)는 그대로예요.");
        output("{\"action\":\"CLARIFY\",\"question\":\"currentAttractions 중 오죽헌(1)과 경포대(2) 중 무엇을 바꿀까요?\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"\"}");
        assertThat(call(Set.of()).question()).isEqualTo("현재 일정의 관광지 중 오죽헌과 경포대 중 무엇을 바꿀까요?");
    }

    @Test void rejectsIdsOutsideListsFixedPlacesMismatchedCountsAndBadTimes() {
        String[] invalid={
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[9],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[1],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\",\"unavailablePlaces\":[],\"exactPlace\":false,\"departAt\":null,\"arriveBy\":null,\"firstArrival\":null,\"transport\":\"BIKE\",\"avoidRestaurants\":[],\"preferRestaurants\":[],\"stayPlaceId\":null}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\",\"unavailablePlaces\":[],\"exactPlace\":false,\"departAt\":null,\"arriveBy\":null,\"firstArrival\":null,\"transport\":null,\"avoidRestaurants\":[99],\"preferRestaurants\":[],\"stayPlaceId\":null}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1,2],\"add\":[3,4],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":\"07:00\",\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":300,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"<b>설명</b>\"}",
            "{\"action\":\"CLARIFY\",\"question\":\"질문\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"\"}",
            "{\"action\":\"DELETE\",\"question\":\"\",\"replace\":[],\"add\":[],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}"};
        for(String json:invalid) {
            output(json);
            assertThatThrownBy(()->call(Set.of())).as(json).isInstanceOfSatisfying(RecommendationException.class,
                e->assertThat(e.getCode()).isEqualTo("AI_INVALID_RESPONSE"));
        }
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[2],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}");
        assertThatThrownBy(()->call(Set.of(2L))).isInstanceOfSatisfying(RecommendationException.class,
            e->assertThat(e.getMessage()).contains("fixedPlaceIds"));
    }

    // "○○도 넣어 줘"처럼 더하기만 하면 replace 없이 add만 받는다. 이동수단 변경도 함께 받는다.
    @Test void acceptsAddOnlyAndTransportChange() {
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"안목해변을 더했어요.\",\"unavailablePlaces\":[],\"exactPlace\":false,\"departAt\":null,\"arriveBy\":null,\"firstArrival\":null,"
            +"\"transport\":\"PUBLIC_TRANSIT\",\"avoidRestaurants\":[],\"preferRestaurants\":[],\"stayPlaceId\":null}");
        var proposal=call(Set.of());
        assertThat(proposal.replace()).isEmpty();
        assertThat(proposal.add()).containsExactly(3L);
        assertThat(proposal.transport()).isEqualTo(KakaoMapClient.Transport.PUBLIC_TRANSIT);
        assertThat(request.get()).contains("restaurants").contains("accommodations").contains("avoidRestaurants");
    }

    // GPT가 "후보에 없다"고 한 장소는 요청 문장에 있고 후보 이름과 겹치지 않는 것만 남긴다. 콕 집었는지도 함께 받는다.
    @Test void keepsOnlyUnavailablePlacesThatAreInTheRequestAndNotCandidates() {
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"오죽헌 대신 안목해변으로 바꿨어요.\",\"unavailablePlaces\":[\"전동성당\",\"경포대\",\"없는 장소\",\"안목\"],\"exactPlace\":true}");
        var proposal=client("test-key").propose(conditions(),input(),plan(),current,spare,Set.of(),"오죽헌을 전동 성당으로 바꿔 줘",null);
        assertThat(proposal.unavailable()).containsExactly("전동성당");
        assertThat(proposal.exactPlace()).isTrue();
        // 남은 이름이 없으면 콕 집었다는 표시도 쓰지 않는다.
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"설명\",\"unavailablePlaces\":[\"경포대\"],\"exactPlace\":true}");
        var none=client("test-key").propose(conditions(),input(),plan(),current,spare,Set.of(),"오죽헌을 경포대 같은 곳으로",null);
        assertThat(none.unavailable()).isEmpty(); assertThat(none.exactPlace()).isFalse();
        assertThat(request.get()).contains("unavailablePlaces").contains("exactPlace");
        // "○○ 같은 곳"은 GPT가 콕 집은 요청이라고 해도 비슷한 곳 요청으로 본다(실제 GPT에서 잘못 분류된 사례).
        output("{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,"
            +"\"explanation\":\"설명\",\"unavailablePlaces\":[\"덕수궁\"],\"exactPlace\":true}");
        var similar=client("test-key").propose(conditions(),input(),plan(),current,spare,Set.of(),"오죽헌 대신 덕수궁 같은 고궁으로 바꿔 주세요",null);
        assertThat(similar.unavailable()).containsExactly("덕수궁"); assertThat(similar.exactPlace()).isFalse();
        assertThat(OpenAiRevisionClient.asksSimilar("덕수궁처럼 조용한 곳","덕수궁")).isTrue();
        assertThat(OpenAiRevisionClient.asksSimilar("오죽헌을 덕수궁으로 바꿔 줘","덕수궁")).isFalse();
    }

    @Test void mapsServiceFailuresAndMissingKey() {
        assertThatThrownBy(()->client("").propose(conditions(),input(),plan(),current,spare,Set.of(),"요청",null))
            .isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_NOT_CONFIGURED"));
        output("{}"); status.set(429);
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(401);
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_AUTH"));
    }
}
