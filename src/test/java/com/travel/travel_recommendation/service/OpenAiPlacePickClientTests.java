package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.OpenAiPlacePickClient.Candidate;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiPlacePickClientTests {
    private final JsonMapper mapper=new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status=new AtomicInteger(200),delay=new AtomicInteger();
    private final AtomicReference<String> response=new AtomicReference<>(),request=new AtomicReference<>();

    private List<Candidate> candidates() {
        return List.of(new Candidate(1,"정동진 해변","해수욕장·해변",18,false),new Candidate(2,"오죽헌","유적지",3,true),
            new Candidate(3,"인월사","절·사찰",5,false),new Candidate(4,"경포해수욕장","해수욕장·해변",0,false));
    }
    private ConfirmedTravelConditions conditions() {
        return new ConfirmedTravelConditions("TEXT","private-original-input","강릉",LocalDate.of(2026,10,1),
            LocalDate.of(2026,10,2),2,null,2,true,null,List.of(),"private-summary");
    }
    @BeforeEach void setup() throws Exception {
        output("{\"order\":[4,1,2],\"skip\":[3]}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{
            request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { Thread.sleep(delay.get());byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } finally { e.close(); }
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiPlacePickClient client(String key,Duration timeout) {
        return new OpenAiPlacePickClient(new OpenAiProperties(key,"gpt-4.1-mini","none"),mapper,
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),timeout);
    }
    private void output(String json) {
        response.set(mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
            List.of(Map.of("type","output_text","text",json)))))));
    }
    private OpenAiPlacePickClient.Picks pick() { return client("test-key",Duration.ofSeconds(2)).pick(conditions(),"강릉","CAR",candidates()); }

    @Test void sendsCandidateNamesOnlyAndReturnsOrderAndSkips() {
        var picks=pick();
        assertThat(picks.order()).containsExactly(4L,1L,2L);
        assertThat(picks.skip()).containsExactly(3L);
        // 후보 이름·분류·거리만 보내고 자연어 원문·해석 요약 같은 개인 입력은 보내지 않는다.
        assertThat(request.get()).contains("정동진 해변","해수욕장·해변","강릉").doesNotContain("private-original-input","private-summary");
    }

    // 다른 OpenAI 호출과 같은 공통 요청 설정을 쓴다. reasoning이 빠지면 느려지고 출력 상한을 생각에 먼저 쓴다.
    @Test void usesCommonRequestSettings() {
        pick();
        var body=mapper.readTree(request.get());
        assertThat(body.path("model").asText()).isEqualTo("gpt-4.1-mini");
        assertThat(body.path("store").booleanValue()).isFalse();
        assertThat(body.path("reasoning").path("effort").asText()).isEqualTo("none");
        assertThat(body.path("max_output_tokens").intValue()).isEqualTo(1500);
    }

    // 비슷한 조건 회원 기록이 있는 관광지에만 회원 수를 붙여 보낸다.
    @Test void memberCountsAreSentOnlyForPlacesWithRecords() {
        var withMembers=List.of(new Candidate(1,"정동진 해변","해수욕장·해변",18,false,new MemberStats(1,0,2)),
            new Candidate(2,"오죽헌","유적지",3,true),new Candidate(3,"인월사","절·사찰",5,false),new Candidate(4,"경포해수욕장","해수욕장·해변",0,false));
        client("test-key",Duration.ofSeconds(2)).pick(conditions(),"강릉","CAR",withMembers);
        var sent=mapper.readTree(mapper.readTree(request.get()).path("input").asText()).path("candidates");
        assertThat(sent.get(0).path("members").toString()).isEqualTo("{\"saved\":1,\"favorited\":0,\"ratedHigh\":2}");
        assertThat(sent.get(1).has("members")).isFalse();
    }

    // 후보에 없는 ID와 중복은 버리고, 갈 만한 곳에 적었거나 사용자가 넣기로 한 곳(fixed)은 뺄 곳에서 뺀다.
    @Test void dropsUnknownDuplicateAndFixedIds() {
        output("{\"order\":[4,99,4,1],\"skip\":[1,2,3,77]}");
        var picks=pick();
        assertThat(picks.order()).containsExactly(4L,1L);
        assertThat(picks.skip()).containsExactly(3L);
    }

    // 밤 명소(night)는 order에 적은 곳만 앞에서부터 3곳까지 받는다. 뺄 곳·모르는 ID는 버리고, 없거나 형식이 다르면 빈 목록이다.
    @Test void readsNightSpotsOnlyFromOrderedPlaces() {
        output("{\"order\":[4,1,2],\"skip\":[3],\"night\":[4,3,99,4,1,2]}");
        assertThat(pick().night()).containsExactly(4L,1L,2L);
        output("{\"order\":[4,1,2],\"skip\":[3]}");
        assertThat(pick().night()).isEmpty();
        // 응답 형식에 night를 넣어 요청하고, 밤에 문을 닫는 곳은 적지 말라고 알린다.
        assertThat(request.get()).contains("\"night\"").contains("야경");
    }

    // 갈 만한 곳이 없거나 후보의 절반 넘게 빼라고 하면 응답을 쓰지 않는다. 호출한 쪽이 서버 규칙으로 고른 관광지를 그대로 쓴다.
    @Test void rejectsEmptyOrderAndTooManySkips() {
        output("{\"order\":[],\"skip\":[3]}");
        assertCode("AI_INVALID_RESPONSE",this::pick);
        output("{\"order\":[2],\"skip\":[1,3,4]}");
        assertCode("AI_INVALID_RESPONSE",this::pick);
        output("{\"order\":\"4\"}");
        assertCode("AI_INVALID_RESPONSE",this::pick);
    }

    @Test void reportsConnectionProblemsWithoutLeakingTheKey() {
        assertCode("AI_NOT_CONFIGURED",()->client(" ",Duration.ofSeconds(2)).pick(conditions(),"강릉","CAR",candidates()));
        status.set(429); assertCode("AI_RATE_LIMIT",()->client("secret-key",Duration.ofSeconds(2)).pick(conditions(),"강릉","CAR",candidates()));
        status.set(401); assertCode("AI_AUTH",()->client("secret-key",Duration.ofSeconds(2)).pick(conditions(),"강릉","CAR",candidates()));
        status.set(200); delay.set(400);
        assertCode("AI_TIMEOUT",()->client("secret-key",Duration.ofMillis(200)).pick(conditions(),"강릉","CAR",candidates()));
    }
    private void assertCode(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RecommendationException.class,error->{
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("secret-key"); assertThat(error.getCause()).isNull();
        });
    }
}
