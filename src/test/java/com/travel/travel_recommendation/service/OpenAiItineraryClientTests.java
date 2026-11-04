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
        return new OpenAiItineraryClient(new OpenAiProperties(key, "gpt-6-luna", "none"),mapper,
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
    // 반려동물 동반 여행이면 서버가 조회한 장소별 동반 정보를 함께 보내고, 아니면 반려동물 항목을 보내지 않는다.
    @Test void sendsPetInfoOnlyForPetTravel() {
        var info=new com.travel.travel_recommendation.dto.PlacePetInfo("129784","전구역 동반가능","전 견종","목줄 착용","",Instant.now());
        client("test-key",Duration.ofSeconds(2)).assist(conditions(),plan().withPets(List.of(new PetCheck(10L,"오죽헌",info))),null);
        // 입력 JSON은 문자열로 감싸여 따옴표가 \"로 들어간다. 지시문에도 같은 낱말이 있어 따옴표까지 맞춰 확인한다.
        assertThat(request.get()).contains("\\\"petTravel\\\":true","\\\"petInfo\\\":\\\"동반 가능 · 전 견종 · 목줄 착용 (한국관광공사)\\\"");
        client("test-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null);
        assertThat(request.get()).doesNotContain("\\\"petTravel\\\"","\\\"petInfo\\\"");
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
    // 추천 활동은 '~요'로 끝나는 문장만 받는다. 지시문이 새어 나온 것 같은 문장은 그 장소만 버린다(일정 안내는 그대로).
    @Test void dropsPlaceActivitiesThatDoNotEndAsSentences() {
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\"}],\"places\":[{\"placeId\":10,\"activity\":\"부드러운 대화 tone\"}]}");
        assertThat(client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null).placeReasons()).isEmpty();
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\"}],\"places\":[{\"placeId\":10,\"activity\":\"몽룡실과 율곡기념관을 둘러보세요.\"}]}");
        assertThat(client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null).placeReasons()).containsEntry(10L,"몽룡실과 율곡기념관을 둘러보세요.");
        // 한자·일본 문자가 섞인 문장도 버린다(2026-11-03 "木立 사이의 숲길").
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\"}],\"places\":[{\"placeId\":10,\"activity\":\"木立 사이의 숲길을 걸어 보세요.\"}]}");
        assertThat(client("key",Duration.ofSeconds(2)).assist(conditions(),plan(),null).placeReasons()).isEmpty();
    }
    // 관광 항목에는 서버가 조회한 관광공사 소개문을 추천 활동의 근거로 보내고, 식사 항목은 활동을 받지 않아 소개문도 보내지 않는다.
    @Test void sendsOverviewsOnlyForVisitsAndAcceptsActivitiesOnlyForVisits() {
        var visit=plan().days().getFirst().items().getFirst();
        var meal=new Item("MEAL",20L,"식당",LocalTime.of(12,0),LocalTime.of(13,0),"식사 · 한식",null,null,null,null);
        var withMeal=new Result("강릉",List.of(new Day(1,LocalDate.of(2026,10,1),List.of(visit,meal))),KakaoMapClient.Transport.CAR,Instant.now());
        output("{\"summary\":\"안내\",\"days\":[{\"number\":1,\"advice\":\"안내\"}],\"places\":[{\"placeId\":10,\"activity\":\"몽룡실을 둘러보세요.\"}]}");
        var assist=client("key",Duration.ofSeconds(2)).assist(conditions(),withMeal,null,Map.of(10L,"율곡 이이가 태어난 몽룡실이 있는 곳", 20L,"식당 소개"));
        assertThat(request.get()).contains("\\\"overview\\\":\\\"율곡 이이가 태어난 몽룡실이 있는 곳\\\"").doesNotContain("식당 소개");
        assertThat(assist.placeReasons()).containsOnlyKeys(10L);
        // 응답 형식(JSON 스키마)도 관광지 ID만 허용한다.
        assertThat(request.get()).contains("\"activity\"").doesNotContain("\"enum\":[10,20]");
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
        // 같은 429라도 크레딧 소진(insufficient_quota)은 기다려도 풀리지 않아 다른 코드로 알린다.
        String ok=response.get();
        response.set("{\"error\":{\"type\":\"insufficient_quota\",\"code\":\"credit_balance_exhausted\"}}");
        assertCode("AI_QUOTA",()->client("secret-key",Duration.ofSeconds(2)).assist(conditions(),plan(),null));
        response.set(ok);
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
