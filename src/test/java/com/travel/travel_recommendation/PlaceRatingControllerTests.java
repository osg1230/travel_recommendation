package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.PlaceRatingView;
import com.travel.travel_recommendation.service.PlaceRatingService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 평가 둘러보기·평가 입력·저장·삭제 화면(DB 없이, 평가 서비스는 흉내 냄). */
@SpringBootTest
@AutoConfigureMockMvc
class PlaceRatingControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean PlaceRatingService ratings;

    @Test void browseRequiresLoginAndListsRecentRatingsByPlace() throws Exception {
        mvc.perform(get("/my/ratings/browse")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        var place=new PlaceRatingView.PlaceInfo(10L,"경포대","ATTRACTION","관광지","강원특별자치도 강릉시 경포로 365","https://map","https://source",true);
        var summary=new PlaceRatingView.Summary(4.5,2,List.of(new PlaceRatingView.TagCount("경치가 좋아요",2),new PlaceRatingView.TagCount("조용해요",1)),List.of(
            new PlaceRatingView.Recent("라**",4,List.of(),null,LocalDateTime.of(2026,10,28,20,49)),
            new PlaceRatingView.Recent("김**",5,List.of("경치가 좋아요"),"노을이 <b>예뻐요</b>",LocalDateTime.of(2026,10,28,20,40))));
        // 검색어는 앞뒤 공백을 지운 뒤 서비스에 넘기고 입력칸에 다시 채운다.
        when(ratings.browse("경포")).thenReturn(List.of(new PlaceRatingView.RatedPlace(place,summary)));
        mvc.perform(get("/my/ratings/browse").param("q"," 경포 ").with(user("member"))).andExpect(status().isOk())
            .andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("경포대")))
            // 많이 고른 태그는 고른 사람 수와 함께 보인다(SFR-10-02).
            .andExpect(content().string(containsString("★ 4.5 · 평가 2개 · 경치가 좋아요 2명, 조용해요 1명")))
            .andExpect(content().string(containsString("라** · 2026-10-28 20:49")))
            .andExpect(content().string(containsString("김** · 2026-10-28 20:40")))
            .andExpect(content().string(containsString("노을이 &lt;b&gt;예뻐요&lt;/b&gt;")))
            .andExpect(content().string(containsString("value=\"경포\"")))
            .andExpect(content().string(containsString("returnTo=/my/ratings/browse")));
        when(ratings.browse("")).thenReturn(List.of());
        mvc.perform(get("/my/ratings/browse").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("아직 남겨진 평가가 없어요")));
        mvc.perform(get("/my/ratings/browse").param("q","없는곳").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("“없는곳”이(가) 든 장소의 평가를 찾지 못했어요")));
        mvc.perform(get("/my/ratings/browse").param("q","가".repeat(51)).with(user("member"))).andExpect(status().isBadRequest());
    }

    @Test void browseKeepsSearchWordInReturnAddress() throws Exception {
        var place=new PlaceRatingView.PlaceInfo(10L,"경포대","ATTRACTION","관광지","강릉","https://map",null,true);
        when(ratings.browse("경포")).thenReturn(List.of(new PlaceRatingView.RatedPlace(place,new PlaceRatingView.Summary(5.0,1,List.of(),List.of()))));
        // 검색어는 퍼센트 인코딩된 채로 returnTo 안에 들어간다(링크 값으로 한 번 더 인코딩된다).
        mvc.perform(get("/my/ratings/browse").param("q","경포").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("returnTo=/my/ratings/browse?q%3D%25EA%25B2%25BD%25ED%258F%25AC")));
    }

    // 저장하면 장소 이름이 든 안내와 함께 보던 일정 항목 위치로 돌아가고, 그 항목 옆에 안내를 보여 줄 위치 이름도 넘긴다.
    @Test void saveReturnsToTheItemWithPlaceName() throws Exception {
        when(ratings.save(eq(10L),eq("member"),eq(4),any(),any())).thenReturn(true);
        when(ratings.placeName(10L)).thenReturn("경포대");
        mvc.perform(post("/my/ratings/10").with(user("member")).with(csrf()).param("score","4").param("returnTo","/my/itineraries/7#stop-1-2"))
            .andExpect(redirectedUrl("/my/itineraries/7#stop-1-2"))
            .andExpect(flash().attribute("savedMessage","‘경포대’ 평가를 등록했어요."))
            .andExpect(flash().attribute("ratingAnchor","stop-1-2"));
        when(ratings.save(eq(10L),eq("member"),eq(5),any(),any())).thenReturn(false);
        mvc.perform(post("/my/ratings/10").with(user("member")).with(csrf()).param("score","5").param("returnTo","/my/ratings/browse?q=%EA%B2%BD"))
            .andExpect(redirectedUrl("/my/ratings/browse?q=%EA%B2%BD"))
            .andExpect(flash().attribute("savedMessage",containsString("새로 고른 내용으로 바꿨어요")))
            .andExpect(flash().attributeCount(1));
        // 허용하지 않은 주소나 모양이 다른 위치(#)는 받지 않고 평가 화면으로 돌아간다.
        for(String outside:new String[]{"https://evil.example","//evil.example","/my/itineraries/7#x","/my/ratings/browse?q=a#b","/admin"})
            mvc.perform(post("/my/ratings/10").with(user("member")).with(csrf()).param("score","5").param("returnTo",outside))
                .andExpect(redirectedUrl("/my/ratings/10"));
        // 즐겨찾기 목록에서 '평가 보기'로 온 경우 즐겨찾기 목록으로 돌아간다.
        mvc.perform(post("/my/ratings/10").with(user("member")).with(csrf()).param("score","5").param("returnTo","/my/favorites"))
            .andExpect(redirectedUrl("/my/favorites"));
    }

    // 저장에 실패하면 적은 별점·태그·한줄평을 flash로 들고 평가 화면으로 돌아가 다시 채운다.
    @Test void failedSaveReturnsToFormWithWhatWasEntered() throws Exception {
        when(ratings.save(eq(10L),eq("member"),any(),any(),any()))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,"화면에 있는 태그에서만 골라 주세요."));
        mvc.perform(post("/my/ratings/10").with(user("member")).with(csrf()).param("score","3").param("tags","7","8")
                .param("comment","바람이 많이 불어요").param("returnTo","/my/itineraries/7#stop-1-2"))
            .andExpect(status().isFound())
            .andExpect(header().string("Location",startsWith("/my/ratings/10?returnTo=")))
            .andExpect(header().string("Location",containsString("stop-1-2")))
            .andExpect(flash().attribute("ratingError","화면에 있는 태그에서만 골라 주세요."))
            .andExpect(flash().attribute("draft",new PlaceRatingView.Draft(3,List.of(7L,8L),"바람이 많이 불어요")));
        // 다른 화면에서 먼저 처음 평가한 경우(409)와 일시적인 DB 오류도 같은 방식으로 돌아간다.
        when(ratings.save(eq(11L),eq("member"),any(),any(),any())).thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 방금 이 장소를 평가했어요."));
        mvc.perform(post("/my/ratings/11").with(user("member")).with(csrf()).param("score","2"))
            .andExpect(redirectedUrl("/my/ratings/11")).andExpect(flash().attribute("ratingError",containsString("다른 화면에서")));
        when(ratings.save(eq(12L),eq("member"),any(),any(),any())).thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(post("/my/ratings/12").with(user("member")).with(csrf()).param("score","2"))
            .andExpect(redirectedUrl("/my/ratings/12")).andExpect(flash().attribute("ratingError",containsString("다시 채워 두었으니")));
        // 장소가 없으면(404) 오류 화면이고, 다시 열어도 같아 '이전 화면으로' 링크는 없다.
        when(ratings.save(eq(13L),eq("member"),any(),any(),any())).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"평가할 수 있는 장소를 찾지 못했어요."));
        mvc.perform(post("/my/ratings/13").with(user("member")).with(csrf()).param("score","2"))
            .andExpect(status().isNotFound()).andExpect(content().string(not(containsString("이전 화면으로"))));
    }

    @Test void formRefillsDraftAndWarnsAboutRetiredTags() throws Exception {
        var place=new PlaceRatingView.PlaceInfo(10L,"경포대","ATTRACTION","관광지","강릉","https://map.kakao.com/link/map/1","https://source",true);
        var tags=List.of(new PlaceRatingView.Tag(1L,"경치가 좋아요"),new PlaceRatingView.Tag(2L,"조용해요"));
        var mine=new PlaceRatingView.Mine(4,List.of(1L,9L),"노을이 예뻐요",LocalDateTime.of(2026,10,28,8,0));
        when(ratings.form(10L,"member")).thenReturn(new PlaceRatingView.Form(place,mine,tags,PlaceRatingView.Summary.EMPTY,List.of("아이와 가기 좋아요")));
        // 기존 평가로 채우고, 사용 중지된 태그는 저장하면 빠진다고 알려 준다. 위치 보기·출처 링크도 있다.
        mvc.perform(get("/my/ratings/10").param("returnTo","/my/itineraries/7#stop-1-2").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("value=\"4\" required checked=\"checked\"")))
            .andExpect(content().string(containsString("value=\"노을이 예뻐요\"")))
            .andExpect(content().string(containsString("‘아이와 가기 좋아요’ 태그는 지금 고를 수 없는 태그라 저장하면 빠져요")))
            .andExpect(content().string(containsString("href=\"https://map.kakao.com/link/map/1\"")))
            .andExpect(content().string(containsString("data-submit-loading")))
            // 삭제 폼도 돌아갈 주소를 함께 보낸다.
            .andExpect(content().string(containsString("name=\"returnTo\" value=\"/my/itineraries/7#stop-1-2\"")));
        // 저장에 실패해 돌아오면 방금 적은 값이 기존 평가보다 우선한다.
        mvc.perform(get("/my/ratings/10").with(user("member"))
                .flashAttr("draft",new PlaceRatingView.Draft(2,List.of(2L),"다시 적은 글")).flashAttr("ratingError","별점을 1~5점 중에서 골라 주세요."))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("value=\"2\" required checked=\"checked\"")))
            .andExpect(content().string(not(containsString("value=\"4\" required checked=\"checked\""))))
            .andExpect(content().string(containsString("value=\"다시 적은 글\"")))
            .andExpect(content().string(containsString("별점을 1~5점 중에서 골라 주세요.")));
    }

    // 더 이상 확인되지 않는 장소는 평가를 바꿀 수 없고 삭제만 할 수 있다.
    @Test void inactivePlaceShowsDeleteOnly() throws Exception {
        var place=new PlaceRatingView.PlaceInfo(10L,"문 닫은 곳","ATTRACTION","관광지","강릉","https://map",null,false);
        var mine=new PlaceRatingView.Mine(3,List.of(),null,LocalDateTime.of(2026,10,28,8,0));
        when(ratings.form(10L,"member")).thenReturn(new PlaceRatingView.Form(place,mine,List.of(),PlaceRatingView.Summary.EMPTY,List.of()));
        mvc.perform(get("/my/ratings/10").with(user("member"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("평가를 새로 남기거나 바꿀 수 없어요")))
            .andExpect(content().string(containsString("내 평가 삭제")))
            .andExpect(content().string(not(containsString("평가 바꾸기"))));
    }

    // 삭제 뒤에도 보던 화면으로 돌아가고, 이미 지운 평가를 다시 지우면 오류 대신 안내한다.
    @Test void deleteReturnsToPreviousScreenAndToleratesRepeat() throws Exception {
        when(ratings.placeName(10L)).thenReturn("경포대");
        when(ratings.delete(10L,"member")).thenReturn(true,false);
        mvc.perform(post("/my/ratings/10/delete").with(user("member")).with(csrf()).param("returnTo","/my/itineraries/7#stop-2-0"))
            .andExpect(redirectedUrl("/my/itineraries/7#stop-2-0"))
            .andExpect(flash().attribute("savedMessage","‘경포대’ 평가를 삭제했어요."))
            .andExpect(flash().attribute("ratingAnchor","stop-2-0"));
        mvc.perform(post("/my/ratings/10/delete").with(user("member")).with(csrf()))
            .andExpect(redirectedUrl("/my/ratings"))
            .andExpect(flash().attribute("savedMessage","‘경포대’ 평가는 이미 삭제됐어요."));
    }

    // DB·트랜잭션 오류는 안내 화면을 보여 주고, 그 장소의 평가 화면으로 돌아갈 링크를 둔다.
    @Test void errorPageLinksBackToTheRatingScreen() throws Exception {
        when(ratings.placeName(10L)).thenReturn("경포대");
        when(ratings.delete(10L,"member")).thenThrow(new DataAccessResourceFailureException("down"));
        mvc.perform(post("/my/ratings/10/delete").with(user("member")).with(csrf()).param("returnTo","/my/favorites"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("이전 화면으로")))
            .andExpect(content().string(containsString("href=\"/my/ratings/10?returnTo=/my/favorites\"")));
        when(ratings.delete(11L,"member")).thenThrow(new CannotCreateTransactionException("no connection"));
        mvc.perform(post("/my/ratings/11/delete").with(user("member")).with(csrf()))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("평가를 처리하지 못했어요")))
            .andExpect(content().string(containsString("href=\"/my/ratings/11\"")));
    }
}
