package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@EnabledIfEnvironmentVariable(named = "RUN_SQLSERVER_TESTS", matches = "true")
@SpringBootTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class PlaceCandidateIntegrationTests {
    // 장소 목록은 후보가 적으면 카카오를 자동 조회한다. 테스트에서 실제 외부 API를 부르지 않도록 빈 결과를 돌려주는 목으로 바꾼다.
    @org.springframework.test.context.bean.override.mockito.MockitoBean com.travel.travel_recommendation.service.KakaoMapClient maps;
    @Autowired RecommendationJobService jobs;
    @Autowired PlaceCandidateService service;
    @Autowired AiJobRepository jobRepository;
    @Autowired UserRepository users;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private final MockHttpSession session = new MockHttpSession();
    private final String token = UUID.randomUUID().toString();

    @BeforeEach void hideExternalCache() { ExternalPlaceCache.expireExisting(jdbc); }

    private byte[] hash() throws Exception {
        session.setAttribute("recommendationGuestToken", token);
        return MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
    }

    private UUID completed(String region, int days, boolean accommodation, String login) throws Exception {
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", region, null, null, days,
            null, 2, accommodation, false, List.of(), "");
        UUID id = jobs.create(conditions, login, login == null ? hash() : null);
        jobs.prepare(id);
        var candidates = jobs.view(id, login, login == null ? hash() : null).candidates();
        List<RankedDestination> ranked = new ArrayList<>();
        for (var candidate : candidates) ranked.add(new RankedDestination(ranked.size() + 1, candidate, "테스트 추천"));
        // 유료 호출 없이 이미 검증된 추천 완료 상태에서 다음 화면을 검사한다.
        jobRepository.findById(id).orElseThrow().completeRecommendation(mapper.writeValueAsString(ranked));
        em.flush();
        return id;
    }

    private long destination(UUID id, String login) throws Exception {
        return jobs.view(id, login, login == null ? hash() : null).recommendations().getFirst().destination().placeId();
    }
    private String url(UUID id, long destinationId) {
        return "/travel/recommendations/" + id + "/destinations/" + destinationId + "/places";
    }

    @Test void allSupportedRegionsRenderOnlyTheirOwnVerifiedPlaces() throws Exception {
        for (String region : List.of("강릉", "부산", "경주")) {
            UUID id = completed(region, 3, true, null);
            long destinationId = destination(id, null);
            var result = service.find(id, destinationId, null, hash());
            assertThat(result.attractions()).hasSize(2);
            assertThat(result.accommodations()).hasSize(1);
            assertThat(result.restaurants()).hasSize(1);
            assertThat(result.attractions()).allSatisfy(place -> {
                assertThat(place.address()).contains(region);
                assertThat(place.mapSearchUrl()).startsWith("https://map.kakao.com/link/search/").doesNotContain(" ", "+");
            });
            mvc.perform(get(url(id, destinationId)).session(session)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().string(containsString(result.attractions().getFirst().name())))
                .andExpect(content().string(containsString(result.restaurants().getFirst().name())))
                .andExpect(content().string(containsString("실시간 조회가 아니에요")))
                .andDo(response -> {
                    if (Boolean.getBoolean("travel.ui.capture")) {
                        var folder = java.nio.file.Path.of("target", "ui-check");
                        java.nio.file.Files.createDirectories(folder);
                        java.nio.file.Files.writeString(folder.resolve(region + ".html"),
                            response.getResponse().getContentAsString(StandardCharsets.UTF_8));
                    }
                });
            mvc.perform(get("/travel/recommendations/" + id).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString(url(id, destinationId))));
        }
    }

    @Test void dayTripsAndNoAccommodationChoiceExcludeHotels() throws Exception {
        for (int days : List.of(1, 3)) {
            UUID id = completed("강릉", days, days == 1, null);
            var result = service.find(id, destination(id, null), null, hash());
            assertThat(result.accommodationNeeded()).isFalse();
            assertThat(result.accommodations()).isEmpty();
            mvc.perform(get(url(id, destination(id, null))).session(session)).andExpect(status().isOk())
                .andExpect(content().string(containsString("숙소 후보를 제외했어요")));
        }
    }

    @Test void differentGuestAndMissingTokenCannotReadPlaces() throws Exception {
        UUID id = completed("부산", 2, true, null);
        String url = url(id, destination(id, null));
        mvc.perform(get(url).session(new MockHttpSession())).andExpect(status().isNotFound());
        var other = new MockHttpSession(); other.setAttribute("recommendationGuestToken", "different");
        mvc.perform(get(url).session(other)).andExpect(status().isNotFound());
    }

    @Test void membersCannotReadEachOthersResultsAndRestrictedOwnerIsDenied() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        var owner = users.saveAndFlush(new User("place_" + suffix, "hash", "장소" + suffix));
        var other = users.saveAndFlush(new User("other_" + suffix, "hash", "다른" + suffix));
        UUID id = completed("경주", 2, true, owner.getLoginId());
        String url = url(id, destination(id, owner.getLoginId()));
        mvc.perform(get(url).with(user(owner.getLoginId()))).andExpect(status().isOk());
        mvc.perform(get(url).with(user(other.getLoginId()))).andExpect(status().isNotFound());
        mvc.perform(get(url).session(session)).andExpect(status().isNotFound());
        jdbc.update("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=?", owner.getId());
        em.clear();
        mvc.perform(get(url).with(user(owner.getLoginId()))).andExpect(status().isForbidden());
    }

    @Test void unrankedRegionAndPendingJobCannotBeSelected() throws Exception {
        UUID id = completed("강릉", 2, true, null);
        long otherId = jdbc.queryForObject("SELECT place_id FROM dbo.places WHERE source_place_id='R1_BUSAN'", Long.class);
        mvc.perform(get(url(id, otherId)).session(session)).andExpect(status().isNotFound());
        var conditions = new ConfirmedTravelConditions("KEYWORD", "", "강릉", null, null, 2,
            null, 2, true, false, List.of(), "");
        UUID pending = jobs.create(conditions, null, hash());
        mvc.perform(get(url(pending, destination(id, null))).session(session)).andExpect(status().isConflict())
            .andExpect(content().string(containsString("여행지 추천을 마친 뒤")));
    }

    @Test void expiredJobAndInactiveDestinationAreRejected() throws Exception {
        UUID id = completed("강릉", 2, true, null);
        long destinationId = destination(id, null);
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_id=?", destinationId); em.clear();
        mvc.perform(get(url(id, destinationId)).session(session)).andExpect(status().isConflict());
        jdbc.update("UPDATE dbo.ai_jobs SET expires_at=DATEADD(hour,-1,SYSDATETIME()) WHERE job_id=?", id); em.clear();
        mvc.perform(get(url(id, destinationId)).session(session)).andExpect(status().isNotFound());
    }

    @Test void unsafeUnverifiedAndInactivePlacesAreOmittedAndEmptyStatesRender() throws Exception {
        UUID id = completed("부산", 2, true, null);
        long destinationId = destination(id, null);
        jdbc.update("UPDATE dbo.places SET source_url='https://www.visitbusan.net.evil.example/' WHERE source_place_id='R1_BS_HAEUNDAE'");
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE source_place_id='R1_BS_GAMCHEON'");
        jdbc.update("UPDATE dbo.places SET address=NULL WHERE source_place_id='R1_BS_SHILLA_STAY'");
        jdbc.update("UPDATE dbo.places SET verified_at=DATEADD(day,1,SYSDATETIME()) WHERE source_place_id='R1_BS_AMSO_GALBI'");
        em.clear();
        var result = service.find(id, destinationId, null, hash());
        assertThat(result.attractions()).isEmpty();
        assertThat(result.accommodations()).isEmpty();
        assertThat(result.restaurants()).isEmpty();
        mvc.perform(get(url(id, destinationId)).session(session)).andExpect(status().isOk())
            .andExpect(content().string(containsString("확인된 관광지 후보가 아직 없어요")))
            .andExpect(content().string(containsString("확인된 음식점 후보가 아직 없어요")))
            .andExpect(content().string(containsString("확인된 숙소 후보가 아직 없어요")));
    }
}
