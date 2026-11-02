package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 같은 초안을 동시에 저장해도 내 일정이 하나만 생기는지 실제 SQL Server에서 확인한다.
 * 잠금은 여러 트랜잭션 사이에서만 보이므로 다른 통합 테스트와 달리 커밋하고, 만든 행은 끝날 때 지운다.
 */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties="spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("sqlserver-windows")
class SavedItineraryRaceIntegrationTests {
    @Autowired RecommendationJobService recommendations;
    @Autowired ItineraryJobService schedules;
    @Autowired SavedItineraryService saved;
    @Autowired AiJobRepository jobs;
    @Autowired ItineraryRepository itineraries;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ObjectMapper mapper;
    @Autowired TransactionTemplate transaction;
    @Autowired JdbcTemplate jdbc;
    @MockitoBean KakaoMapClient maps;
    @MockitoBean DestinationLandmarkService landmarks;
    @MockitoBean TourApiClient tour;
    private User owner;

    private record Draft(String input, String result) {}

    @BeforeEach void setup() {
        when(maps.locate(anyString())).thenAnswer(call->new KakaoMapClient.Location(call.getArgument(0),128.9,37.7,Instant.now()));
        when(maps.travelTime(any(),any(),any())).thenAnswer(call->new KakaoMapClient.TravelTime(call.getArgument(2),300,3000,Instant.now()));
        String suffix=UUID.randomUUID().toString().replace("-","");
        owner=users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
    }
    @AfterEach void cleanup() {
        Long id=owner.getId();
        jdbc.update("DELETE i FROM dbo.itinerary_items i JOIN dbo.itineraries t ON t.itinerary_id=i.itinerary_id WHERE t.user_id=?",id);
        jdbc.update("DELETE FROM dbo.itineraries WHERE user_id=?",id);
        jdbc.update("DELETE FROM dbo.ai_jobs WHERE user_id=?",id);
        jdbc.update("DELETE c FROM dbo.recommendation_candidates c JOIN dbo.recommendation_requests r ON r.request_id=c.request_id WHERE r.user_id=?",id);
        jdbc.update("DELETE FROM dbo.recommendation_requests WHERE user_id=?",id);
        jdbc.update("DELETE FROM dbo.users WHERE user_id=?",id);
    }

    // 초안 내용은 되돌리는 트랜잭션에서 만든다. 가짜 좌표의 장소 정보가 개발 DB에 커밋되지 않게 하기 위해서다.
    private Draft draftContent() {
        return transaction.execute(status->{
            status.setRollbackOnly();
            var start=LocalDate.now().plusDays(7);
            var conditions=new ConfirmedTravelConditions("KEYWORD","","강릉",start,start.plusDays(1),2,null,2,true,false,List.of(),"");
            var parent=recommendations.create(conditions,owner.getLoginId(),null);
            recommendations.prepare(parent);
            var candidate=recommendations.view(parent,owner.getLoginId(),null).candidates().getFirst();
            jobs.findById(parent).orElseThrow().completeRecommendation(
                mapper.writeValueAsString(List.of(new RankedDestination(1,candidate,"추천"))));
            jobs.flush();
            var id=schedules.create(parent,candidate.placeId(),owner.getLoginId(),null,LocalTime.of(9,0),LocalTime.of(18,0),
                KakaoMapClient.Transport.CAR,90);
            var work=schedules.claim(id);
            schedules.complete(work,schedules.generate(work));
            jobs.flush();
            var job=jobs.findById(id).orElseThrow();
            return new Draft(job.getRequestJson(),job.getResultJson());
        });
    }
    private UUID committedDraft(Draft draft) {
        var expires=LocalDateTime.now().plusHours(1);
        var parent=jobs.saveAndFlush(AiJob.restoredRecommendation(owner.getId(),"{}","[]",expires));
        return jobs.saveAndFlush(AiJob.restoredItinerary(parent,draft.input(),draft.result())).getId();
    }

    @Test void savingTheSameDraftAtOnceCreatesOneItinerary() throws Exception {
        var draft=draftContent();
        // 경합은 타이밍에 달려 있어 여러 번 겨룬다. 잠금이 없으면 한 번만 겹쳐도 일정이 둘 이상 생긴다.
        var pool=Executors.newFixedThreadPool(4);
        try {
            for(int round=0;round<5;round++) {
                var jobId=committedDraft(draft);
                var start=new CountDownLatch(1);
                var attempts=new ArrayList<Future<SavedItineraryService.Saved>>();
                for(int i=0;i<4;i++) attempts.add(pool.submit(()->{
                    start.await();
                    return saved.save(jobId,owner.getLoginId());
                }));
                start.countDown();
                var results=new ArrayList<SavedItineraryService.Saved>();
                for(var attempt:attempts) results.add(attempt.get(30,TimeUnit.SECONDS));
                assertThat(results.stream().filter(SavedItineraryService.Saved::created)).hasSize(1);
                assertThat(results.stream().map(SavedItineraryService.Saved::id).distinct()).hasSize(1);
                assertThat(itineraries.findByUserIdOrderByStartDateDescIdDesc(owner.getId())).hasSize(1);
                // 판마다 내 일정이 하나인지 세도록 이번 일정을 지운다.
                saved.delete(results.getFirst().id(),owner.getLoginId());
            }
        } finally { pool.shutdownNow(); }
    }
}
