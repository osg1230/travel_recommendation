package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 문장으로 입력한 전국 요청에서 AI가 여행지별 근거를 보고 고른 여행지가 요청 스냅샷에 남고, 후보 5곳 맨 앞에 오는지
 * 실제 SQL Server에서 확인한다. GPT는 모의 객체다.
 */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@ActiveProfiles("sqlserver-windows")
@Transactional
class DestinationPickIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired DestinationPickService picks;
    @Autowired DestinationCandidateService destinations;
    @Autowired AiJobRepository jobs;
    @Autowired ObjectMapper mapper;
    @MockitoBean OpenAiDestinationPickClient ai;
    private final byte[] hash=new byte[32];

    @BeforeEach void setup() {
        Arrays.fill(hash,(byte)7);
        when(ai.configured()).thenReturn(true);
    }
    private UUID request(String inputMode,String region) {
        var conditions=new ConfirmedTravelConditions(inputMode,"단풍 보러 산에 가고 싶어요",region,null,null,2,null,2,true,null,List.of(),"",
            true,"단풍 등산");
        return recommendations.create(conditions,null,hash);
    }
    private ConfirmedTravelConditions conditions(UUID id) {
        return mapper.readValue(jobs.findById(id).orElseThrow().getRequestJson(),ConfirmedTravelConditions.class);
    }

    @Test void pickedDestinationsAreSavedAndComeFirstInCandidates() {
        var all=destinations.usable();
        var chosen=List.of(all.get(all.size()-1).placeId(),all.get(all.size()-2).placeId());
        when(ai.pick(any(),anyList())).thenReturn(chosen);
        var id=request("TEXT","");
        picks.enrich(id);
        var pick=conditions(id).destinationPick();
        assertThat(pick.status()).isEqualTo("PICKED");
        assertThat(pick.destinationIds()).isEqualTo(chosen);
        // 등록 순서가 가장 뒤인 두 곳도 AI가 고른 순서대로 맨 앞에 오고, 남는 자리는 기존 기준으로 채운다.
        assertThat(destinations.find(conditions(id))).extracting(DestinationCandidate::placeId).startsWith(chosen.toArray(Long[]::new)).hasSize(5);
        // 전체 여행지를 근거와 함께 보냈다.
        verify(ai).pick(any(),argThat(options->options.size()==Math.min(all.size(),50)));
        // 이미 고른 요청은 다시 묻지 않는다.
        picks.enrich(id);
        verify(ai,times(1)).pick(any(),anyList());
    }

    // 조건 선택(버튼) 입력과 여행지가 5곳 이하인 지역은 AI에게 묻지 않는다.
    @Test void keywordInputAndSmallRegionsAreSkipped() {
        picks.enrich(request("KEYWORD",""));
        picks.enrich(request("TEXT","강릉"));
        verify(ai,never()).pick(any(),anyList());
    }

    // AI가 실패하면 실패로 남기고, 후보는 기존 기준으로 5곳을 고른다.
    @Test void failureIsRecordedAndCandidatesUseTheExistingOrder() {
        when(ai.pick(any(),anyList())).thenThrow(new RecommendationException("AI_TIMEOUT","후보 고르기 응답 시간이 초과됐어요."));
        var id=request("TEXT","");
        picks.enrich(id);
        var c=conditions(id);
        assertThat(c.destinationPick().status()).isEqualTo("FAILED");
        assertThat(c.pickedDestinationIds()).isEmpty();
        assertThat(destinations.find(c)).hasSize(5);
    }
}
