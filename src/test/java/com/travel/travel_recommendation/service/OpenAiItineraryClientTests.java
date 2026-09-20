package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiItineraryClientTests {
    private final JsonMapper mapper=new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger(),delay=new AtomicInteger();
    private final AtomicReference<String> response=new AtomicReference<>(),request=new AtomicReference<>();

    private Result plan() {
        var item=new Item("VISIT",10L,"오죽헌",LocalTime.of(9,0),LocalTime.of(10,30),"관광·관람",
            "https://map.kakao.com/","https://www.gn.go.kr/",null,null);
        return new Result("강릉",List.of(new Day(1,LocalDate.of(2026,10,1),List.of(item))),
            KakaoMapClient.Transport.CAR,Instant.now());
    }
    private ConfirmedTravelConditions conditions() {
        return new ConfirmedTravelConditions("TEXT","private-original-input","강릉",LocalDate.of(2026,10,1),
            LocalDate.of(2026,10,1),1,null,2,false,null,List.of(),"private-summary");
    }
    @BeforeEach void setup() throws Exception {
        output("{\"summary\":\"하루 일정이라 이동이 적어요.\",\"days\":[{\"number\":1,\"advice\":\"편한 신발을 준비해 주세요.\"}]}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{
            calls.incrementAndGet();request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { Thread.sleep(delay.get());byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } finally { e.close(); }
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiItineraryClient client(String key,Duration timeout) {
        return new OpenAiItineraryClient(new OpenAiProperties(key,"gpt-4.1-mini"),mapper,
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),timeout);
    }
    private void output(String json) {
        response.set(mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
            List.of(Map.of("type","output_text","text",json)))))));
    }
    @Test void sendsOnlyPlanFactsAndReturnsDayAdvice() {
        var assist=client("test-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null);
        assertThat(assist.summary()).isEqualTo("하루 일정이라 이동이 적어요.");
        assertThat(assist.days()).singleElement().satisfies(day->{
            assertThat(day.number()).isEqualTo(1);
            assertThat(day.advice()).isEqualTo("편한 신발을 준비해 주세요.");
        });
        assertThat(assist.createdAt()).isNotNull();
        // 자연어 원문·해석 요약 같은 개인 입력은 보내지 않는다.
        assertThat(request.get()).contains("오죽헌","강릉","09:00").doesNotContain("private-original-input","private-summary");
    }
    @Test void resendsPreviousFailureReasonForSelfCorrection() {
        client("test-key",Duration.ofSeconds(2)).assist(conditions(),plan(),"일차 번호는 일정에 있는 번호를 한 번씩만 사용해야 해요.");
        assertThat(request.get()).contains("previousFailure","한 번씩만");
    }
    @Test void rejectsAdviceThatDoesNotMatchTheServerPlan() {
        output("{\"summary\":\"안내\",\"days\":[{\"number\":2,\"advice\":\"다른 날 안내\"}]}");
        assertCode("AI_INVALID_RESPONSE",()->client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\"},{\"number\":1,\"advice\":\"안내\"}]}");
        assertCode("AI_INVALID_RESPONSE",()->client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        output("{\"summary\":\"안내\",\"days\":[]}");
        assertCode("AI_INVALID_RESPONSE",()->client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
    }
    @Test void rejectsMarkupLinksAndEmptyOrOversizedText() {
        for(String advice:new String[]{"<b>안내</b>","https://example.com 확인","mail@example.com 문의","   ","안".repeat(601)}) {
            output(mapper.writeValueAsString(Map.of("summary","안내","days",List.of(Map.of("number",1,"advice",advice)))));
            assertCode("AI_INVALID_RESPONSE",()->client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        }
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\",\"extra\":\"추가\"}]}");
        assertCode("AI_INVALID_RESPONSE",()->client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
    }
    @Test void reportsConfigurationAndServiceFailuresSeparately() {
        assertCode("AI_NOT_CONFIGURED",()->client("",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        assertThat(calls.get()).isZero();
        status.set(429); assertCode("AI_RATE_LIMIT",()->client("secret-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        status.set(401); assertCode("AI_AUTH",()->client("secret-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        status.set(503); assertCode("AI_UNAVAILABLE",()->client("secret-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        status.set(200); delay.set(400);
        assertCode("AI_TIMEOUT",()->client("secret-key",Duration.ofMillis(200)).assist(conditions(),plan(),null));
    }
    private void assertCode(String code,org.assertj.core.api.ThrowableAssert.ThrowingCallable action) {
        assertThatThrownBy(action).isInstanceOfSatisfying(RecommendationException.class,error->{
            assertThat(error.getCode()).isEqualTo(code);
            assertThat(error.getMessage()).doesNotContain("secret-key"); assertThat(error.getCause()).isNull();
        });
    }
}
