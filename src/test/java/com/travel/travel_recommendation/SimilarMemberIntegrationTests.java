package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.*;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 비슷한 조건으로 여행한 다른 회원의 기록(저장 일정·즐겨찾기·4점 이상 평가)이 후보 5곳을 고를 때 가점이 되고,
 * 추천 카드에 회원 수로 보이는지 실제 SQL Server에서 확인한다. 테스트 데이터는 트랜잭션이 끝나면 되돌린다.
 */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class SimilarMemberIntegrationTests {
    private static final LocalDate START=LocalDate.of(2026,11,14);
    @Autowired SimilarMemberService similarMembers;
    @Autowired DestinationCandidateService destinations;
    @Autowired RecommendationJobService recommendations;
    @Autowired DestinationRecommendationService ranking;
    @Autowired AiJobRepository jobs;
    @Autowired UserRepository users;
    @Autowired ItineraryRepository itineraries;
    @Autowired ItineraryItemRepository items;
    @Autowired FavoriteRepository favorites;
    @Autowired PlaceRatingRepository ratings;
    @Autowired KeywordRepository keywords;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    private User member(String prefix) {
        String suffix=UUID.randomUUID().toString().substring(0,8);
        return users.saveAndFlush(new User("test_"+prefix+"_"+suffix,encoder.encode("Travel123!"),"비슷_"+prefix+suffix));
    }
    // 강원(여행지 8곳)에서 1박 2일, 동행자·계절만 고른 조건. 여행 취향이 없어 후보 순서는 회원 가점과 등록 순서로만 정해진다.
    private ConfirmedTravelConditions conditions() {
        var choices=keywords.findActiveChoices();
        var selected=List.of(first(choices,"COMPANION"),first(choices,"SEASON"));
        return new ConfirmedTravelConditions("KEYWORD",null,"강원",START,START.plusDays(1),2,null,2,true,null,selected,"",false,null);
    }
    private static TravelKeyword first(List<TravelKeyword> choices,String category) {
        return choices.stream().filter(k->category.equals(k.categoryCode())).findFirst().orElseThrow();
    }
    private static List<Long> ids(List<DestinationCandidate> candidates) { return candidates.stream().map(DestinationCandidate::placeId).toList(); }

    @Test void similarMembersRecordsRaiseADestinationIntoTheCandidatesAndShowOnTheCard() throws Exception {
        var me=member("me"); var other=member("ot");
        var c=conditions();
        var all=ids(destinations.matching(c));
        assertThat(all.size()).isGreaterThan(5);
        var before=ids(destinations.find(c,me.getId()));
        var existing=similarMembers.destinations(c,me.getId(),all);
        // 아직 후보 5곳에 들지 못했고 기존 회원 기록도 없는 여행지 중 가장 뒤의 곳을 고른다.
        Long target=all.reversed().stream().filter(id->!before.contains(id) && !existing.containsKey(id)).findFirst().orElseThrow();
        Long attraction=jdbc.queryForObject("select top 1 place_id from places where place_type='ATTRACTION' and is_active=1 order by place_id",Long.class);
        var placeBefore=similarMembers.places(c,me.getId(),List.of(attraction)).getOrDefault(attraction,new MemberStats(0,0,0));

        // 다른 회원이 같은 조건으로 추천을 받아 그 여행지 일정을 저장하고 여행지를 즐겨찾기한다.
        // 일정에 넣은 관광지는 5점으로 평가한다(여행지는 평가 대상이 아니다).
        var otherJob=recommendations.create(c,other.getLoginId(),null);
        var itinerary=itineraries.saveAndFlush(new Itinerary(other.getId(),jobs.findById(otherJob).orElseThrow().getRequestId(),target,
            "테스트 일정",START,START.plusDays(1),"CAR","{}"));
        items.saveAndFlush(new ItineraryItem(itinerary.getId(),1,1,"VISIT",attraction,START.atTime(10,0),START.atTime(11,0),"관광",0,null));
        favorites.saveAndFlush(new Favorite(other.getId(),target));
        ratings.saveAndFlush(new PlaceRating(other.getId(),attraction,5,null));

        assertThat(similarMembers.destinations(c,me.getId(),List.of(target))).containsEntry(target,new MemberStats(1,1,0));
        var placeAfter=similarMembers.places(c,me.getId(),List.of(attraction)).get(attraction);
        assertThat(placeAfter.saved()).isEqualTo(placeBefore.saved()+1);
        assertThat(placeAfter.ratedHigh()).isEqualTo(placeBefore.ratedHigh()+1);
        // 가점(상한 2)으로 후보 5곳 안에 들어온다.
        var after=ids(destinations.find(c,me.getId()));
        assertThat(after).hasSize(5).contains(target);
        // 본인 기록은 세지 않는다.
        assertThat(similarMembers.destinations(c,other.getId(),List.of(target))).doesNotContainKey(target);

        // 추천을 마치면 카드에 서버가 센 회원 수가 보인다(순위·이유는 AI 대신 후보 순서로 채운다).
        var myJob=recommendations.create(c,me.getLoginId(),null);
        recommendations.prepare(myJob);
        jobs.findById(myJob).orElseThrow().queueRecommendation();
        var work=ranking.claim(myJob);
        assertThat(work.members()).containsEntry(target,new MemberStats(1,1,0));
        var ranked=new ArrayList<RankedDestination>();
        for(var candidate:work.candidates())
            ranked.add(new RankedDestination(ranked.size()+1,candidate,candidate.name()+"에서 걷기 좋은 곳을 둘러볼 수 있어요.")
                .withMembers(work.members().get(candidate.placeId())));
        ranking.complete(work,ranked);
        mvc.perform(get("/travel/recommendations/"+myJob).with(user(me.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("비슷한 조건으로 여행한 회원 기록 · 일정 저장 1명 · 즐겨찾기 1명")));

        // 이용이 제한된 회원의 기록은 세지 않는다.
        other.changeStatus(UserStatus.RESTRICTED);
        users.saveAndFlush(other);
        assertThat(similarMembers.destinations(c,me.getId(),List.of(target))).doesNotContainKey(target);
    }
}
