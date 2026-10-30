package com.travel.travel_recommendation.service;

import com.sun.net.httpserver.HttpServer;
import com.travel.travel_recommendation.config.OpenAiProperties;
import com.travel.travel_recommendation.dto.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;

class OpenAiRecommendationClientTests {
    private final JsonMapper mapper=new JsonMapper();
    private HttpServer server;
    private final AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger(),delay=new AtomicInteger();
    private final AtomicReference<String> response=new AtomicReference<>(),request=new AtomicReference<>();
    private final List<DestinationCandidate> pool=List.of(new DestinationCandidate(1L,"강릉","강원", "바다와 커피", "https://visitgangneung.net/",LocalDateTime.of(2026,9,11,0,0)));
    private ConfirmedTravelConditions conditions() {
        return new ConfirmedTravelConditions("TEXT","private-original-input","강릉",null,null,3,null,2,true,null,List.of(),"private-summary");
    }
    @BeforeEach void setup() throws Exception {
        output("{\"recommendations\":[{\"placeId\":1,\"reason\":\"바다 여행을 즐기기 좋은 후보입니다.\",\"places\":[\"경포해변\",\"<b>\",\"경포해변\",\"오\"]}]}");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/responses",e->{
            calls.incrementAndGet();request.set(new String(e.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            try { Thread.sleep(delay.get());byte[] bytes=response.get().getBytes(StandardCharsets.UTF_8);
                e.sendResponseHeaders(status.get(),bytes.length);e.getResponseBody().write(bytes);
            } catch(InterruptedException ex) { Thread.currentThread().interrupt(); } finally { e.close(); }
        });server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private OpenAiRecommendationClient client(String key,Duration timeout) {
        return new OpenAiRecommendationClient(new OpenAiProperties(key, "gpt-6-luna", "none"),mapper,
            URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/responses"),timeout);
    }
    private void output(String json) {
        response.set(mapper.writeValueAsString(Map.of("status","completed","output",List.of(Map.of("type","message","content",
            List.of(Map.of("type","output_text","text",json)))))));
    }
    @Test void suggestedDirectionsAreOfferedAsChoicesAndOnlyListedOnesAreKept() {
        long id=pool.getFirst().placeId();
        output("{\"recommendations\":[{\"placeId\":"+id+",\"direction\":\"가을 바다 걷기\",\"reason\":\"가을 바다를 걷기 좋아요.\",\"places\":[]}]}");
        var suggested=conditions().withPurpose("단풍 숲길 산책 / 가을 바다 걷기",true);
        var result=client("test-key",Duration.ofSeconds(2)).recommend(suggested,pool);
        assertThat(result.getFirst().direction()).isEqualTo("가을 바다 걷기");
        // 제안 방향 목록을 고를 수 있는 값으로 넘긴다.
        var item=mapper.readTree(request.get()).path("text").path("format").path("schema").path("properties").path("recommendations").path("items");
        assertThat(item.path("properties").path("direction").path("enum").toString()).isEqualTo("[\"단풍 숲길 산책\",\"가을 바다 걷기\"]");
        // 목록에 없는 방향은 버리고, 사용자가 직접 말한 목적에는 방향을 묻지 않는다.
        output("{\"recommendations\":[{\"placeId\":"+id+",\"direction\":\"지어낸 방향\",\"reason\":\"좋아요.\",\"places\":[]}]}");
        assertThat(client("test-key",Duration.ofSeconds(2)).recommend(suggested,pool).getFirst().direction()).isNull();
        assertThat(OpenAiRecommendationClient.directions(conditions().withPurpose("상어 관람 / 바다"))).isEmpty();
    }
    @Test void sendsOnlyConfirmedConditionsAndCandidateFacts() {
        var result=client("test-key",Duration.ofSeconds(2)).recommend(conditions().withPurpose("상어 관람"),pool);
        assertThat(result).hasSize(1);assertThat(result.getFirst().rank()).isEqualTo(1);
        // GPT가 적은 장소 이름은 중복·태그·한 글자를 빼고 받는다. 일정의 중심 장소를 고르는 근거로만 쓴다.
        assertThat(result.getFirst().centerPlaces()).containsExactly("경포해변");
        assertThat(result.getFirst().destination()).isEqualTo(pool.getFirst());
        var body=mapper.readTree(request.get());
        assertThat(body.path("store").booleanValue()).isFalse();
        assertThat(body.path("text").path("format").path("strict").booleanValue()).isTrue();
        assertThat(request.get()).doesNotContain("private-original-input","private-summary","test-key","guestToken","userId");
        assertThat(mapper.readTree(body.path("input").asText()).path("conditions").path("travelPurpose").asText()).isEqualTo("상어 관람");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void rejectsUnknownDuplicateMissingAndMalformedResults() {
        var client=client("test-key",Duration.ofSeconds(2));
        for(String json:List.of("{}","{\"recommendations\":[]}",
            "{\"recommendations\":[{\"placeId\":99,\"reason\":\"없는 후보\",\"places\":[]}]}",
            "{\"recommendations\":[{\"placeId\":1,\"reason\":\" \",\"places\":[]}]}",
            "{\"recommendations\":[{\"placeId\":1,\"reason\":\"<script>bad</script>\",\"places\":[]}]}",
            "{\"recommendations\":[{\"placeId\":\"1\",\"reason\":\"wrong type\",\"places\":[]}]}",
            "{\"recommendations\":[{\"placeId\":1,\"reason\":\"x\",\"name\":\"fake\"}]}")) {
            output(json);assertThatThrownBy(()->client.recommend(conditions(),pool)).isInstanceOf(RecommendationException.class);
        }
        var second=new DestinationCandidate(2L,"부산","부산","해변", "https://www.visitbusan.net/kr/index.do",pool.getFirst().verifiedAt());
        output("{\"recommendations\":[{\"placeId\":1,\"reason\":\"x\",\"places\":[]},{\"placeId\":1,\"reason\":\"y\",\"places\":[]}]}");
        assertThatThrownBy(()->client.recommend(conditions(),List.of(pool.getFirst(),second))).isInstanceOf(RecommendationException.class);
    }
    @Test void promptFieldNamesInReasonAreReplacedWithReadableWords() {
        output("{\"recommendations\":[{\"placeId\":1,\"reason\":\"purposePlaces의 야경 명소가 있어 `travelPurpose`와 THEME에 맞습니다.\",\"places\":[]}]}");
        var result=client("test-key",Duration.ofSeconds(2)).recommend(conditions(),pool);
        assertThat(result.getFirst().reason())
            .isEqualTo("목적 시설 검색 결과의 야경 명소가 있어 여행 목적과 여행 취향에 맞습니다.");
        var body=mapper.readTree(request.get());
        assertThat(body.path("instructions").asText()).contains("입력 필드 이름을 쓰지 말고");
        // 지시문의 예시(예: 상어 관람)가 다른 목적의 추천 이유에 새어 나오지 않도록 구체적 대상을 쓰지 않는다.
        assertThat(body.path("instructions").asText()).doesNotContain("상어").contains("travelPurpose에 없는 목적을 언급하지 않는다");
        // 추천 이유가 "소개에 명시되어" 같은 말로 입력 자료를 가리키지 않고, 확인을 미루는 말 대신 근거로 맞는 점을 쓰게 한다.
        assertThat(body.path("instructions").asText()).contains("주어진 데이터를 가리키지 않는다").contains("확인을 미루는 말은 쓰지 않는다")
            .doesNotContain("확인하지 못했다고 쓴다");
        assertThat(OpenAiRecommendationClient.readable("THEMES와 purposePlacesX는 필드가 아니에요."))
            .isEqualTo("THEMES와 purposePlacesX는 필드가 아니에요.");
        assertThat(OpenAiRecommendationClient.readable("partySize가 없고 COMPANION는 연인, purposeKeywords로 찾았고 budgetKrw를 몰라요."))
            .isEqualTo("인원이 없고 동행자는 연인, 목적 검색어로 찾았고 예산을 몰라요.");
        assertThat(OpenAiRecommendationClient.readable("travelPurpose이나 THEME에 맞춰"))
            .isEqualTo("여행 목적이나 여행 취향에 맞춰");
    }
    // 근거 장소를 함께 보내고, 확인을 미루는 이유는 한 번만 다시 요청한 뒤 남은 그런 문장을 뺀다.
    @Test void evidenceIsSentAndHedgedReasonIsRetriedOnceThenTrimmed() {
        var evidence=Map.of(1L,List.of(new DestinationProfile.Evidence("해변·해수욕장",12,List.of("경포해변","강문해변","안목해변"))));
        output("{\"recommendations\":[{\"placeId\":1,\"reason\":\"경포해변이 있어 바다를 보기 좋습니다. 다만 운영 시간은 방문 전 확인이 필요합니다.\",\"places\":[]}]}");
        var result=client("test-key",Duration.ofSeconds(2)).recommend(conditions(),pool,Map.of(),evidence);
        assertThat(calls.get()).isEqualTo(2);
        assertThat(result.getFirst().reason()).isEqualTo("경포해변이 있어 바다를 보기 좋습니다.");
        var body=mapper.readTree(request.get());
        assertThat(body.path("instructions").asText()).startsWith("이전 답의 reason에 확인을 미루는 말이 있었다.");
        assertThat(mapper.readTree(body.path("input").asText()).path("candidates").get(0).path("evidence").get(0).path("names").toString())
            .isEqualTo("[\"경포해변\",\"강문해변\",\"안목해변\"]");
        // 모든 문장이 확인을 미루면 근거 장소로, 근거도 없으면 여행지 소개로 바꾼다.
        output("{\"recommendations\":[{\"placeId\":1,\"reason\":\"바다를 볼 만한 장소는 찾지 못했습니다.\",\"places\":[]}]}");
        assertThat(client("test-key",Duration.ofSeconds(2)).recommend(conditions(),pool,Map.of(),evidence).getFirst().reason())
            .isEqualTo("경포해변·강문해변 등 해변·해수욕장을 둘러볼 수 있는 곳이에요.");
        assertThat(client("test-key",Duration.ofSeconds(2)).recommend(conditions(),pool).getFirst().reason()).isEqualTo("바다와 커피");
        assertThat(calls.get()).isEqualTo(6);
    }
    @Test void hedgeSentencesAreFoundAndRemoved() {
        assertThat(ReasonHedges.found("등산로는 확인되지 않았습니다.")).isTrue();
        assertThat(ReasonHedges.found("설악산 국립공원이 있어 등산하기 좋습니다.")).isFalse();
        assertThat(ReasonHedges.without("설악산이 있어 좋습니다. 숙박은 확인이 필요합니다. 가을 단풍도 아름답습니다."))
            .isEqualTo("설악산이 있어 좋습니다. 가을 단풍도 아름답습니다.");
        assertThat(ReasonHedges.without("상어 전시는 알 수 없습니다.")).isEmpty();
    }
    @Test void errorsRefusalAndIncompleteAreSafeWithoutAutomaticRetry() {
        var client=client("test-key",Duration.ofSeconds(2));
        response.set("private-provider-body");
        for(int code:List.of(401,403,429,500)) {
            status.set(code);assertThatThrownBy(()->client.recommend(conditions(),pool))
                .isInstanceOf(RecommendationException.class).hasMessageNotContaining("private-provider-body");
        }
        assertThat(calls.get()).isEqualTo(4);status.set(200);
        for(String text:List.of("bad-json","{\"status\":\"incomplete\"}",
            "{\"status\":\"completed\",\"output\":[{\"content\":[{\"type\":\"refusal\"}]}]}")) {
            response.set(text);assertThatThrownBy(()->client.recommend(conditions(),pool)).isInstanceOf(RecommendationException.class);
        }
    }
    @Test void missingKeyMakesNoCallAndTimeoutStopsOnce() {
        assertThatThrownBy(()->client("",Duration.ofSeconds(2)).recommend(conditions(),pool)).isInstanceOf(RecommendationException.class);
        assertThat(calls.get()).isZero();delay.set(700);
        assertThatThrownBy(()->client("test-key",Duration.ofMillis(100)).recommend(conditions(),pool)).hasMessageContaining("초과");
        assertThat(calls.get()).isLessThanOrEqualTo(1);
    }
}
