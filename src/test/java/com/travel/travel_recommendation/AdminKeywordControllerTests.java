package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.AdminKeyword;
import com.travel.travel_recommendation.service.AdminKeywordService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class AdminKeywordControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean AdminKeywordService service;
    private static final String LOCKED="지역·계절·이동수단은 추천 규칙이 이름과 값을 그대로 쓰므로 이름을 바꾸거나 새 키워드를 추가할 수 없어요.";

    private List<AdminKeyword.Category> catalog() {
        return List.of(
            new AdminKeyword.Category(1L,"REGION","지역",1,true,LOCKED,
                List.of(new AdminKeyword.Item(11L,"SEOUL","서울",1,true,LOCKED))),
            new AdminKeyword.Category(4L,"THEME","여행 취향",4,true,null,
                List.of(new AdminKeyword.Item(40L,"NIGHT_VIEW","야경",7,false,null))));
    }

    @Test void onlyAdminsReachKeywordManagement() throws Exception {
        mvc.perform(get("/admin/keywords")).andExpect(status().isFound()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin/keywords").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/keywords/40/active").with(user("member").roles("MEMBER")).with(csrf())
                .param("active","false")).andExpect(status().isForbidden());
        // CSRF 토큰 없는 변경 요청은 관리자라도 막는다.
        mvc.perform(post("/admin/keywords").with(user("boss").roles("ADMIN"))
                .param("categoryId","4").param("code","NIGHT").param("name","야경")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void listsCategoriesAndOffersAddOnlyForOpenCategories() throws Exception {
        when(service.catalog("boss")).thenReturn(catalog());
        mvc.perform(get("/admin/keywords").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("서울")))
            .andExpect(content().string(containsString("이름 고정")))
            .andExpect(content().string(containsString("사용 중지")))
            // 추가 폼의 카테고리 선택지에는 여행 취향만 있고 지역은 없다.
            .andExpect(content().string(matchesRegex("(?s).*<option value=\"4\">여행 취향</option>.*")))
            .andExpect(content().string(not(containsString("<option value=\"1\">"))));
    }

    @Test void addsUpdatesAndChangesActiveWithResultMessages() throws Exception {
        when(service.addKeyword(4L,"NIGHT_VIEW","야경","","boss")).thenReturn(40L);
        mvc.perform(post("/admin/keywords").with(user("boss").roles("ADMIN")).with(csrf())
                .param("categoryId","4").param("code","NIGHT_VIEW").param("name","야경").param("sortOrder",""))
            .andExpect(redirectedUrl("/admin/keywords/40"))
            .andExpect(flash().attribute("adminMessage",containsString("추가했어요")));
        when(service.updateKeyword(40L,"밤 풍경","8","boss")).thenReturn(true);
        mvc.perform(post("/admin/keywords/40").with(user("boss").roles("ADMIN")).with(csrf())
                .param("name","밤 풍경").param("sortOrder","8"))
            .andExpect(redirectedUrl("/admin/keywords/40"))
            .andExpect(flash().attribute("adminMessage",containsString("반영돼요")));
        // 이름이 고정된 키워드는 이름 없이 순서만 보낸다.
        mvc.perform(post("/admin/keywords/11").with(user("boss").roles("ADMIN")).with(csrf()).param("sortOrder","1"))
            .andExpect(flash().attribute("adminMessage","바뀐 내용이 없어요."));
        verify(service).updateKeyword(11L,null,"1","boss");
        mvc.perform(post("/admin/keywords/40/active").with(user("boss").roles("ADMIN")).with(csrf()).param("active","false"))
            .andExpect(redirectedUrl("/admin/keywords/40"))
            .andExpect(flash().attribute("adminMessage",containsString("선택지에서 빠져요")));
        verify(service).changeKeywordActive(40L,false,"boss");
        mvc.perform(post("/admin/keywords/categories/4/active").with(user("boss").roles("ADMIN")).with(csrf()).param("active","false"))
            .andExpect(redirectedUrl("/admin/keywords/categories/4"));
        verify(service).changeCategoryActive(4L,false,"boss");
    }

    @Test void showsNameFieldOnlyWhenRenameIsAllowed() throws Exception {
        when(service.keyword(11L,"boss")).thenReturn(new AdminKeyword.KeywordDetail(
            new AdminKeyword.Item(11L,"SEOUL","서울",1,true,LOCKED),"지역",true,LocalDateTime.of(2026,10,28,9,0)));
        mvc.perform(get("/admin/keywords/11").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("(고정)")))
            .andExpect(content().string(not(containsString("name=\"name\""))))
            .andExpect(content().string(containsString("사용 중지")));
        when(service.keyword(40L,"boss")).thenReturn(new AdminKeyword.KeywordDetail(
            new AdminKeyword.Item(40L,"NIGHT_VIEW","야경",7,false,null),"여행 취향",false,LocalDateTime.of(2026,10,28,9,0)));
        mvc.perform(get("/admin/keywords/40").with(user("boss").roles("ADMIN")))
            .andExpect(content().string(containsString("name=\"name\"")))
            .andExpect(content().string(containsString("다시 사용")))
            .andExpect(content().string(containsString("카테고리가 사용 중지 상태")));
        when(service.category(4L,"boss")).thenReturn(new AdminKeyword.CategoryDetail(
            4L,"THEME","여행 취향",4,true,7,LocalDateTime.of(2026,10,28,9,0)));
        mvc.perform(get("/admin/keywords/categories/4").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("소속 키워드")))
            .andExpect(content().string(containsString("카테고리 사용 중지")));
    }

    @Test void explainsRejectedChanges() throws Exception {
        when(service.addKeyword(eq(1L),anyString(),anyString(),anyString(),eq("boss")))
            .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST,LOCKED));
        mvc.perform(post("/admin/keywords").with(user("boss").roles("ADMIN")).with(csrf())
                .param("categoryId","1").param("code","MOKPO").param("name","목포"))
            .andExpect(status().isBadRequest()).andExpect(content().string(containsString("새 키워드를 추가할 수 없어요")))
            .andExpect(content().string(containsString("이전 화면으로 돌아가기")));
        when(service.updateKeyword(eq(40L),anyString(),anyString(),eq("boss")))
            .thenThrow(new DataIntegrityViolationException("UQ_keywords_name"));
        mvc.perform(post("/admin/keywords/40").with(user("boss").roles("ADMIN")).with(csrf())
                .param("name","바다").param("sortOrder","2"))
            .andExpect(status().isConflict()).andExpect(content().string(containsString("이미 있어요")));
    }
}
