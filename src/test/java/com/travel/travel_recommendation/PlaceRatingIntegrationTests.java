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
    // 개발 DB의 장소를 쓰면 시연 중 남긴 평가와 섞이므로 테스트 장소를 새로 넣는다(롤백됨).
    private long placeOf(String type) {
        return jdbc.queryForObject("""
            INSERT INTO dbo.places(place_type,source_provider,source_place_id,place_name,region,address,subtype,description,source_url,verified_at)
            OUTPUT INSERTED.place_id
            VALUES(?,'SYSTEM',?,N'평가 테스트 장소',N'강원특별자치도 강릉시',N'강원특별자치도 강릉시 경포로 1',N'테스트',N'테스트 장소',N'https://www.gn.go.kr/',DATEADD(day,-1,SYSDATETIME()))""",
            Long.class,type,"TEST_RATING_"+UUID.randomUUID().toString().substring(0,8));
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
        // 다른 회원은 내 평가를 지울 수 없고(지울 자기 평가가 없어 false), 자기 평가만 지운다.
        var third=member();
        assertThat(ratings.delete(attraction,third.getLoginId())).isFalse();
        assertThat(ratings.delete(attraction,other.getLoginId())).isTrue();
        clear();
        // 두 번 누르거나 다른 탭에서 먼저 지웠으면 오류 없이 false다.
        assertThat(ratings.delete(attraction,other.getLoginId())).isFalse();
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
        // 별점 없이 보내면 오류 화면 대신 평가 화면으로 돌아가 안내한다(적은 값은 flash로 다시 채운다).
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/ratings/"+attraction)).andExpect(flash().attribute("ratingError",containsString("별점")));
        mvc.perform(get("/my/ratings/"+destination).with(user(owner.getLoginId()))).andExpect(status().isNotFound());
        mvc.perform(post("/my/ratings/"+attraction+"/delete").with(user(owner.getLoginId())).with(csrf()))
            .andExpect(redirectedUrl("/my/ratings")).andExpect(flash().attribute("savedMessage",containsString("평가를 삭제했어요")));
        // 이미 지운 평가를 다시 지우면(두 번 누름) 오류 대신 안내하고, 보던 화면으로 돌아간다.
        mvc.perform(post("/my/ratings/"+attraction+"/delete").with(user(owner.getLoginId())).with(csrf()).param("returnTo","/my/itineraries/12#stop-1-0"))
            .andExpect(redirectedUrl("/my/itineraries/12#stop-1-0")).andExpect(flash().attribute("savedMessage",containsString("이미 삭제됐어요")));
    }

    // 이용 제한·탈퇴 회원의 한줄평은 공개 목록에 내보내지 않는다. 별점·태그는 그대로 보이고 집계에도 들어간다.
    @Test void hidesCommentsOfMembersWhoAreNotActive() {
        var restricted=member(); var active=member();
        ratings.save(attraction,restricted.getLoginId(),1,List.of(tag("VIEW")),"부적절한 한줄평");
        ratings.save(attraction,active.getLoginId(),5,List.of(),"좋았어요");
        jdbc.update("UPDATE dbo.users SET status='RESTRICTED' WHERE user_id=?",restricted.getId());
        clear();
        var summary=ratings.summaries(List.of(attraction)).get(attraction);
        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.average()).isEqualTo(3.0);
        assertThat(summary.recent()).extracting(Recent::comment).containsExactly("좋았어요",null);
        assertThat(summary.recent().get(1).score()).isEqualTo(1);
        assertThat(summary.recent().get(1).tags()).containsExactly("경치가 좋아요");
        // 평가 둘러보기와 평가 화면의 요약도 같은 규칙이다.
        assertThat(ratings.browse(ratings.form(attraction,owner.getLoginId()).place().name())).filteredOn(p->p.place().placeId().equals(attraction))
            .singleElement().satisfies(p->assertThat(p.summary().recent()).extracting(Recent::comment).doesNotContain("부적절한 한줄평"));
        assertThat(ratings.form(attraction,owner.getLoginId()).summary().recent()).extracting(Recent::comment).doesNotContain("부적절한 한줄평");
    }

    // 더 이상 확인되지 않는(비활성) 장소의 평가는 바꿀 수 없지만 화면을 열어 삭제할 수 있다.
    @Test void inactivePlaceRatingCanOnlyBeDeleted() throws Exception {
        ratings.save(attraction,owner.getLoginId(),4,List.of(),null);
        jdbc.update("UPDATE dbo.places SET is_active=0 WHERE place_id=?",attraction);
        clear();
        var form=ratings.form(attraction,owner.getLoginId());
        assertThat(form.place().active()).isFalse();
        assertThat(form.mine().score()).isEqualTo(4);
        assertThat(ratings.mine(owner.getLoginId())).singleElement().satisfies(r->assertThat(r.place().active()).isFalse());
        assertThatThrownBy(()->ratings.save(attraction,owner.getLoginId(),5,List.of(),null)).isInstanceOf(ResponseStatusException.class);
        mvc.perform(get("/my/ratings/"+attraction).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString("평가를 새로 남기거나 바꿀 수 없어요")));
        // 평가하지 않은 회원에게는 여전히 열리지 않는다.
        assertThatThrownBy(()->ratings.form(attraction,member().getLoginId())).isInstanceOf(ResponseStatusException.class);
        assertThat(ratings.delete(attraction,owner.getLoginId())).isTrue();
        clear();
        assertThatThrownBy(()->ratings.form(attraction,owner.getLoginId())).isInstanceOf(ResponseStatusException.class);
    }

    // 내 평가에 사용 중지된 태그가 있으면 화면에서 '저장하면 빠진다'고 알릴 수 있게 이름을 돌려준다.
    @Test void reportsRetiredTagsInMyRating() {
        ratings.save(attraction,owner.getLoginId(),4,List.of(tag("VIEW"),tag("KIDS")),null);
        jdbc.update("UPDATE dbo.rating_tags SET is_active=0 WHERE tag_code='KIDS'");
        clear();
        var form=ratings.form(attraction,owner.getLoginId());
        String kids=tags.findById(tag("KIDS")).orElseThrow().getName();
        assertThat(form.droppedTags()).containsExactly(kids);
        assertThat(form.tags()).extracting(com.travel.travel_recommendation.dto.PlaceRatingView.Tag::name).doesNotContain(kids);
        assertThat(form.mine().tagIds()).contains(tag("KIDS"));
    }

    // 저장 일정 상세의 '내 평가 ★4'용 별점과 위치 보기 주소.
    @Test void collectsMyScoresAndPlaceLinks() {
        ratings.save(attraction,owner.getLoginId(),4,List.of(),null);
        ratings.save(restaurant,member().getLoginId(),2,List.of(),null);
        clear();
        assertThat(ratings.myScores(List.of(attraction,restaurant),owner.getLoginId())).containsExactly(Map.entry(attraction,4));
        assertThat(ratings.myScores(List.of(attraction),null)).isEmpty();
        assertThat(ratings.form(attraction,owner.getLoginId()).place().mapUrl()).startsWith("https://");
        assertThat(ratings.mine(owner.getLoginId())).singleElement().satisfies(r->assertThat(r.place().mapUrl()).startsWith("https://"));
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
        // 너무 긴 한줄평은 저장하지 않고, 적은 글을 그대로 들고 평가 화면으로 돌아간다.
        String tooLong="가".repeat(PlaceRatingService.MAX_COMMENT_LENGTH+1);
        mvc.perform(post("/my/ratings/"+attraction).with(user(owner.getLoginId())).with(csrf()).param("score","4").param("comment",tooLong))
            .andExpect(redirectedUrl("/my/ratings/"+attraction)).andExpect(flash().attribute("ratingError",containsString("한줄평")))
            .andExpect(flash().attribute("draft",new Draft(4,List.of(),tooLong)));
        mvc.perform(get("/my/ratings/"+attraction).with(user(owner.getLoginId())).flashAttr("draft",new Draft(4,List.of(),tooLong)))
            .andExpect(status().isOk()).andExpect(content().string(containsString("value=\""+tooLong+"\"")));
        clear();
        assertThat(ratings.form(attraction,owner.getLoginId()).mine().comment()).isEqualTo("바다가 바로 앞이에요");
    }

    @Test void browseListsRecentlyRatedPlacesAndFiltersByName() throws Exception {
        ratings.save(restaurant,owner.getLoginId(),4,List.of(tag("TASTY")),"양이 많아요");
        Thread.sleep(5); // 마지막 평가 시각 순서를 확실히 하려고 조금 기다린다.
        ratings.save(attraction,member().getLoginId(),5,List.of(),null);
        clear();
        // 가장 최근에 평가가 남은 장소가 먼저 오고, 장소마다 요약과 최근 평가가 함께 온다.
        // 서버를 켜 둔 채 돌리면 다른 평가가 끼어들 수 있어, 이 테스트의 두 장소만 골라 상대 순서를 본다(점검 보고 24번).
        var all=ratings.browse("").stream().filter(p->List.of(attraction,restaurant).contains(p.place().placeId())).toList();
        assertThat(all).extracting(p->p.place().placeId()).containsExactly(attraction,restaurant);
        assertThat(all.get(1).summary().recent()).singleElement().extracting(Recent::comment).isEqualTo("양이 많아요");
        assertThat(all.get(1).summary().recent().get(0).raterName()).isEqualTo("여**");
        // 장소 이름 일부로 좁힌다. 없는 이름이면 비어 있다.
        String name=all.get(1).place().name();
        assertThat(ratings.browse(name)).extracting(p->p.place().placeId()).contains(restaurant);
        assertThat(ratings.browse("없는장소이름zzz")).isEmpty();
        mvc.perform(get("/my/ratings/browse").param("q",name).with(user(owner.getLoginId()))).andExpect(status().isOk())
            .andExpect(content().string(containsString(name))).andExpect(content().string(containsString("양이 많아요")));
    }
}
