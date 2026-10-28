package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.PlaceRatingView.*;
import com.travel.travel_recommendation.entity.User;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.PlaceRatingService;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 장소 평가 등록·요약·갱신·삭제(SFR-10-01~04)를 실제 SQL Server에서 확인한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class PlaceRatingIntegrationTests {
    @Autowired PlaceRatingService ratings;
    @Autowired PlaceRatingRepository repository;
    @Autowired RatingTagRepository tags;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private User owner;
    private long attraction, restaurant, destination;

    @BeforeEach void setup() {
        owner=member();
        attraction=placeOf("ATTRACTION"); restaurant=placeOf("RESTAURANT"); destination=placeOf("DESTINATION");
    }
    private long placeOf(String type) {
        return jdbc.queryForObject("SELECT TOP 1 place_id FROM dbo.places WHERE place_type=? AND is_active=1 ORDER BY place_id",Long.class,type);
    }
    private User member() {
        String suffix=UUID.randomUUID().toString().replace("-","");
        return users.saveAndFlush(new User("test_"+suffix,encoder.encode("Travel123!"),"여행_"+suffix));
    }
    private Long tag(String code) { return tags.findAll().stream().filter(t->t.getCode().equals(code)).findFirst().orElseThrow().getId(); }
    private void clear() { em.flush(); em.clear(); }

    @Test void registersAndSummarizesAverageCountAndTopTags() {
        assertThat(ratings.summaries(List.of(attraction))).isEmpty();
        assertThat(ratings.save(attraction,owner.getLoginId(),5,List.of(tag("VIEW"),tag("PHOTO")),null)).isTrue();
        var other=member();
        ratings.save(attraction,other.getLoginId(),2,List.of(tag("VIEW")),null);
        clear();
        var summary=ratings.summaries(List.of(attraction)).get(attraction);
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.average()).isEqualTo(3.5);
        assertThat(summary.topTags()).first().isEqualTo(new TagCount("경치가 좋아요",2));
        assertThat(summary.topTags()).extracting(TagCount::name).containsExactly("경치가 좋아요","사진 찍기 좋아요");
        // 음식점도 평가할 수 있고, 여행지(지역)는 평가 대상이 아니다.
        ratings.save(restaurant,owner.getLoginId(),4,List.of(tag("TASTY")),null);
        assertThatThrownBy(()->ratings.save(destination,owner.getLoginId(),4,List.of(),null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("관광지·음식점·숙소만");
    }

    @Test void ratingAgainUpdatesTheSameRecordAndReplacesTags() {
        ratings.save(attraction,owner.getLoginId(),5,List.of(tag("VIEW"),tag("PHOTO"),tag("REVISIT")),null);
        clear();
        long before=repository.count();
        assertThat(ratings.save(attraction,owner.getLoginId(),3,List.of(tag("CLEAN")),null)).isFalse();
        clear();
        assertThat(repository.count()).isEqualTo(before);
        var form=ratings.form(attraction,owner.getLoginId());
        assertThat(form.mine().score()).isEqualTo(3);
        assertThat(form.mine().tagIds()).containsExactly(tag("CLEAN"));
        var summary=ratings.summaries(List.of(attraction)).get(attraction);
        assertThat(summary.average()).isEqualTo(3.0);
        assertThat(summary.topTags()).extracting(TagCount::name).containsExactly("시설이 깨끗해요");
    }

    @Test void rejectsInvalidScoresTagsAndDuplicates() {
        for(Integer score:new Integer[]{null,0,6})
            assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),score,List.of(),null)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("별점");
        assertThat(ratings.save(attraction,owner.getLoginId(),1,List.of(),null)).isTrue();
        assertThat(ratings.save(restaurant,owner.getLoginId(),5,List.of(),null)).isTrue();
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),4,List.of(tag("VIEW"),tag("PHOTO"),tag("CLEAN"),tag("KIDS")),null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("최대 3개");
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),4,List.of(tag("VIEW"),tag("VIEW")),null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("두 번");
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),4,List.of(999999L),null))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("태그");
        jdbc.update("UPDATE dbo.rating_tags SET is_active=0 WHERE tag_code='KIDS'");
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),4,List.of(tag("KIDS")),null))
            .isInstanceOf(ResponseStatusException.class);
        // DB도 슬롯 4번과 여행지 평가를 막는다.
        clear();
        Long ratingId=repository.findByUserIdAndPlaceId(owner.getId(),attraction).orElseThrow().getId();
        assertThatThrownBy(()->jdbc.update("INSERT INTO dbo.place_rating_tags(rating_id,tag_id,slot_no) VALUES(?,?,4)",ratingId,tag("VIEW")))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO dbo.place_ratings(user_id,place_id,score) VALUES(?,?,3)",owner.getId(),destination))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(()->jdbc.update("INSERT INTO dbo.place_ratings(user_id,place_id,score) VALUES(?,?,3)",owner.getId(),attraction))
            .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void deletesOnlyOwnRatingAndRecalculates() {
        var other=member();
        ratings.save(attraction,owner.getLoginId(),5,List.of(tag("VIEW")),null);
        ratings.save(attraction,other.getLoginId(),1,List.of(),null);
        clear();
        // 다른 회원은 내 평가를 지울 수 없고, 자기 평가만 지운다.
        var third=member();
        assertThatThrownBy(()->ratings.delete(attraction,third.getLoginId())).isInstanceOf(ResponseStatusException.class).hasMessageContaining("내 평가가 없어요");
        ratings.delete(attraction,other.getLoginId());
        clear();
        var summary=ratings.summaries(List.of(attraction)).get(attraction);
        assertThat(summary.count()).isEqualTo(1);
        assertThat(summary.average()).isEqualTo(5.0);
        ratings.delete(attraction,owner.getLoginId());
        clear();
        assertThat(ratings.summaries(List.of(attraction))).isEmpty();
        assertThat(ratings.mine(owner.getLoginId())).isEmpty();
    }

    @Test void screensRequireLoginAndKeepReturnPathsInsideService() throws Exception {
        mvc.perform(get("/my/ratings")).andExpect(status().is3xxRedirection());
        mvc.perform(get("/my/ratings/"+attraction).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("평가 등록"))).andExpect(content().string(containsString("경치가 좋아요")));
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId()))).andExpect(status().isForbidden());
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()).param("score","4")
                .param("tags",String.valueOf(tag("VIEW"))).param("returnTo","https://evil.example"))
            .andExpect(redirectedUrl("/my/ratings/"+attraction));
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()).param("score","5").param("returnTo","/my/itineraries/12"))
            .andExpect(redirectedUrl("/my/itineraries/12"));
        mvc.perform(get("/my/ratings").with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("★★★★★")));
        if(Boolean.getBoolean("travel.ui.capture")) {
            var html=mvc.perform(get("/my/ratings/"+attraction).with(user(owner.getLoginId()))).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
            var folder=java.nio.file.Path.of("target","ui-check"); java.nio.file.Files.createDirectories(folder);
            java.nio.file.Files.writeString(folder.resolve("rating.html"),html);
        }
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()))
            .andExpect(status().isBadRequest()).andExpect(content().string(containsString("별점")));
        mvc.perform(get("/my/ratings/"+destination).with(user(owner.getLoginId()))).andExpect(status().isNotFound());
        mvc.perform(post("/my/ratings/"+attraction+"/delete").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/ratings"));
    }

    @Test void savesTrimmedCommentAndClearsItWhenBlank() {
        assertThat(ratings.save(attraction,owner.getLoginId(),4,List.of(tag("VIEW")),"  노을 무렵이 제일 예뻐요  ")).isTrue();
        clear();
        assertThat(ratings.form(attraction,owner.getLoginId()).mine().comment()).isEqualTo("노을 무렵이 제일 예뻐요");
        assertThat(ratings.mine(owner.getLoginId())).singleElement().extracting(MyRating::comment).isEqualTo("노을 무렵이 제일 예뻐요");
        // 다시 평가하면 한줄평도 새 입력으로 바뀌고, 공백만 적으면 비운 것으로 본다.
        assertThat(ratings.save(attraction,owner.getLoginId(),4,List.of(),"   ")).isFalse();
        clear();
        assertThat(ratings.form(attraction,owner.getLoginId()).mine().comment()).isNull();
        assertThat(jdbc.queryForObject("SELECT comment FROM dbo.place_ratings WHERE user_id=? AND place_id=?",String.class,owner.getId(),attraction)).isNull();
    }

    @Test void rejectsCommentLongerThanLimit() {
        String limit="가".repeat(PlaceRatingService.MAX_COMMENT_LENGTH);
        assertThat(ratings.save(attraction,owner.getLoginId(),3,List.of(),limit)).isTrue();
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),3,List.of(),limit+"나"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("한줄평");
        clear();
        assertThat(ratings.form(attraction,owner.getLoginId()).mine().comment()).isEqualTo(limit);
    }

    @Test void summaryListsRecentRatingsWithMaskedNicknamesAndComments() {
        // 7명이 평가하면 최근에 바꾼 순서로 5건만 보인다. 마지막 평가자만 태그·한줄평을 남긴다.
        var members=new ArrayList<User>();
        for(int i=0;i<7;i++) {
            var rater=member(); members.add(rater);
            ratings.save(attraction,rater.getLoginId(),i%5+1,i==6?List.of(tag("VIEW"),tag("PHOTO")):List.of(),i==6?"또 오고 싶어요":null);
        }
        clear();
        var recent=ratings.summaries(List.of(attraction)).get(attraction).recent();
        assertThat(recent).hasSize(PlaceRatingService.RECENT_LIMIT);
        assertThat(recent).extracting(Recent::score).containsExactly(2,1,5,4,3);
        var newest=recent.get(0);
        assertThat(newest.comment()).isEqualTo("또 오고 싶어요");
        assertThat(newest.tags()).containsExactly("경치가 좋아요","사진 찍기 좋아요");
        assertThat(newest.updatedAt()).isNotNull();
        // 한줄평이 없는 평가도 별점은 보이고, 평가자는 가린 닉네임("여**")으로만 보인다.
        assertThat(recent.get(1).comment()).isNull();
        assertThat(recent.get(1).tags()).isEmpty();
        assertThat(recent).extracting(Recent::raterName).containsOnly("여**");
        assertThat(recent).extracting(Recent::raterName).doesNotContainAnyElementsOf(members.stream().map(User::getNickname).toList());
        // 평가가 없는 장소는 요약 자체가 없고, 평가 화면의 요약도 같은 최근 평가를 담는다.
        assertThat(ratings.summaries(List.of(restaurant))).isEmpty();
        assertThat(ratings.form(attraction,owner.getLoginId()).summary().recent()).hasSize(PlaceRatingService.RECENT_LIMIT);
    }

    @Test void screensSaveAndShowComment() throws Exception {
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()).param("score","4").param("comment","  바다가 바로 앞이에요  "))
            .andExpect(redirectedUrl("/my/ratings/"+attraction));
        mvc.perform(get("/my/ratings/"+attraction).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("value=\"바다가 바로 앞이에요\"")));
        mvc.perform(get("/my/ratings").with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("바다가 바로 앞이에요")));
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()).param("score","4").param("comment","가".repeat(PlaceRatingService.MAX_COMMENT_LENGTH+1)))
            .andExpect(status().isBadRequest()).andExpect(content().string(containsString("한줄평")));
    }
}
