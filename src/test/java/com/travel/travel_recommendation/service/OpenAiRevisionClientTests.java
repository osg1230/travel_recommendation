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
        response.set(mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
            List.of(Map.of("type","output_text","text",json)))))));
    }
    private OpenAiRevisionClient.Proposal call(Set<Long> fixed) {
        return client("test-key").propose(conditions(),input(),plan(),current,spare,fixed,"박물관 빼고 바다로 바꿔 줘",null);
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

    @Test void rejectsIdsOutsideListsFixedPlacesMismatchedCountsAndBadTimes() {
        String[] invalid={
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[9],\"add\":[3],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[1],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
            "{\"action\":\"REVISE\",\"question\":\"\",\"replace\":[1],\"add\":[3,4],\"start\":null,\"end\":null,\"visitMinutes\":null,\"explanation\":\"설명\"}",
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

    @Test void mapsServiceFailuresAndMissingKey() {
        assertThatThrownBy(()->client("").propose(conditions(),input(),plan(),current,spare,Set.of(),"요청",null))
            .isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_NOT_CONFIGURED"));
        output("{}"); status.set(429);
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_RATE_LIMIT"));
        status.set(401);
        assertThatThrownBy(()->call(Set.of())).isInstanceOfSatisfying(RecommendationException.class,e->assertThat(e.getCode()).isEqualTo("AI_AUTH"));
    }
}
