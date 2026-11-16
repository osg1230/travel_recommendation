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
import org.springframework.transaction.CannotCreateTransactionException;
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
    private static final String LOCKED="지역·계절·이동수단·여행 분위기·여행 속도는 추천 규칙이 이름과 값을 그대로 쓰므로 이름을 바꾸거나 새 키워드를 추가할 수 없어요.";

    private List<AdminKeyword.Category> catalog() {
        return List.of(
            new AdminKeyword.Category(1L,"REGION","지역",1,true,LOCKED,
                List.of(new AdminKeyword.Item(11L,"SEOUL","서울",1,true,LOCKED))),
            new AdminKeyword.Category(4L,"THEME","여행 취향",4,true,null,
                List.of(new AdminKeyword.Item(40L,"NIGHT_VIEW","야경",7,false,null))));
    }

    @Test void onlyAdminsReachKeywordManagement() throws Exception {
        mvc.perform(get("/admin/keywords")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
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

    @Test void explainsRejectedChangesAndKeepsWhatWasTyped() throws Exception {
        // 거절되면 원래 화면으로 돌아가 이유를 보이고, 적은 값을 다시 채운다.
        when(service.addKeyword(eq(4L),anyString(),anyString(),anyString(),eq("boss")))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"같은 카테고리에 이미 있는 코드예요. 다른 코드를 입력해 주세요."));
        mvc.perform(post("/admin/keywords").with(user("boss").roles("ADMIN")).with(csrf())
                .param("categoryId","4").param("code","NIGHT").param("name","밤바다").param("sortOrder","12"))
            .andExpect(redirectedUrl("/admin/keywords#add-title"))
            .andExpect(flash().attribute("keywordError",containsString("이미 있는 코드")))
            .andExpect(flash().attribute("inputName","밤바다"))
            .andExpect(flash().attribute("inputCode","NIGHT"))
            .andExpect(flash().attribute("inputCategoryId",4L));
        when(service.catalog("boss")).thenReturn(catalog());
        mvc.perform(get("/admin/keywords").with(user("boss").roles("ADMIN")).flashAttr("keywordError","이미 있는 코드예요.")
                .flashAttr("inputCategoryId",4L).flashAttr("inputCode","NIGHT").flashAttr("inputName","밤바다").flashAttr("inputSortOrder","12"))
            .andExpect(content().string(containsString("이미 있는 코드예요.")))
            .andExpect(content().string(containsString("value=\"밤바다\"")))
            .andExpect(content().string(containsString("value=\"NIGHT\"")))
            .andExpect(content().string(containsString("value=\"12\"")))
            .andExpect(content().string(matchesRegex("(?s).*<option value=\"4\" selected=\"selected\">여행 취향</option>.*")));
        // 동시에 같은 이름을 저장해 DB가 막아도 같은 화면에서 다시 고칠 수 있다.
        when(service.updateKeyword(eq(40L),anyString(),anyString(),eq("boss")))
            .thenThrow(new DataIntegrityViolationException("UQ_keywords_name"));
        mvc.perform(post("/admin/keywords/40").with(user("boss").roles("ADMIN")).with(csrf())
                .param("name","바다").param("sortOrder","2"))
            .andExpect(redirectedUrl("/admin/keywords/40"))
            .andExpect(flash().attribute("keywordError",containsString("이미 있어요")))
            .andExpect(flash().attribute("inputName","바다"))
            .andExpect(flash().attribute("inputSortOrder","2"));
        when(service.keyword(40L,"boss")).thenReturn(new AdminKeyword.KeywordDetail(
            new AdminKeyword.Item(40L,"NIGHT_VIEW","야경",7,true,null),"여행 취향",true,LocalDateTime.of(2026,10,28,9,0)));
        mvc.perform(get("/admin/keywords/40").with(user("boss").roles("ADMIN"))
                .flashAttr("keywordError","같은 이름이나 코드가 이미 있어요.").flashAttr("inputName","바다").flashAttr("inputSortOrder","2"))
            .andExpect(content().string(containsString("같은 이름이나 코드가 이미 있어요.")))
            .andExpect(content().string(containsString("value=\"바다\"")))
            .andExpect(content().string(containsString("value=\"2\"")));
        when(service.updateCategory(eq(4L),anyString(),anyString(),eq("boss")))
            .thenThrow(new ResponseStatusException(HttpStatus.CONFLICT,"이미 있는 카테고리 이름이에요. 다른 이름을 입력해 주세요."));
        mvc.perform(post("/admin/keywords/categories/4").with(user("boss").roles("ADMIN")).with(csrf())
                .param("name","지역").param("sortOrder","4"))
            .andExpect(redirectedUrl("/admin/keywords/categories/4"))
            .andExpect(flash().attribute("inputName","지역"));
    }

    @Test void otherFailuresStillShowTheErrorPage() throws Exception {
        when(service.catalog("boss")).thenThrow(new CannotCreateTransactionException("private details"));
        mvc.perform(get("/admin/keywords").with(user("boss").roles("ADMIN")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("잠시 후 다시 시도")))
            .andExpect(content().string(not(containsString("private details"))));
        when(service.keyword(99L,"boss")).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND,"키워드를 찾을 수 없어요."));
        mvc.perform(get("/admin/keywords/99").with(user("boss").roles("ADMIN")))
            .andExpect(status().isNotFound()).andExpect(content().string(containsString("이전 화면으로 돌아가기")));
    }
}
