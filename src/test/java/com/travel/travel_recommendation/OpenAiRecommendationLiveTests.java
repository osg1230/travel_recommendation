package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.service.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="RUN_OPENAI_RECOMMENDATION_TESTS",matches="true")
@SpringBootTest(properties="travel.jobs.enabled=false")
@ActiveProfiles("sqlserver-windows")
class OpenAiRecommendationLiveTests {
    @Autowired OpenAiRecommendationClient client;
    @Autowired DestinationCandidateService places;
    @Test void ranksVerifiedRegionsWithOnePaidRequest() {
        var conditions=new ConfirmedTravelConditions("KEYWORD","","",null,null,3,null,2,true,null,List.of(),"");
        var candidates=places.find("");var result=client.recommend(conditions,candidates);
        assertThat(result).hasSameSizeAs(candidates);
        assertThat(result).extracting(r->r.destination().placeId()).containsExactlyInAnyOrderElementsOf(candidates.stream().map(DestinationCandidate::placeId).toList());
        assertThat(result).allSatisfy(r->assertThat(r.reason()).isNotBlank());
    }
}
