package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.AdminMember;
import com.travel.travel_recommendation.service.AdminMemberService;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class AdminMemberControllerTests {
    @Autowired MockMvc mvc;
    @MockitoBean AdminMemberService service;

    private AdminMember.Summary summary(String loginId, String status, String statusName) {
        return new AdminMember.Summary(5L,loginId,"여행자","회원",status,statusName,LocalDateTime.of(2026,9,1,0,0));
    }

    @Test void onlyAdminsReachMemberManagement() throws Exception {
        mvc.perform(get("/admin/members")).andExpect(status().isFound()).andExpect(redirectedUrl("/login?required"));
        mvc.perform(get("/admin/members").with(user("member").roles("MEMBER"))).andExpect(status().isForbidden());
        mvc.perform(post("/admin/members/5/status").with(user("boss").roles("ADMIN"))
                .param("status","RESTRICTED").param("reason","약관 위반")).andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }

    @Test void searchesAndShowsMembers() throws Exception {
        when(service.search("여행","ACTIVE",0,"boss")).thenReturn(new AdminMember.Page(
            List.of(summary("traveler01","ACTIVE","정상")),0,1,1,"여행","ACTIVE"));
        mvc.perform(get("/admin/members").with(user("boss").roles("ADMIN"))
                .param("keyword","여행").param("status","ACTIVE"))
            .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
            .andExpect(content().string(containsString("traveler01")))
            .andExpect(content().string(containsString("검색 결과 1명")));
    }

    @Test void showsEmptyResultAndStatusChangeForm() throws Exception {
        when(service.search(null,null,0,"boss")).thenReturn(new AdminMember.Page(List.of(),0,0,0,"",""));
        mvc.perform(get("/admin/members").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk()).andExpect(content().string(containsString("조건에 맞는 회원이 없어요")));
        when(service.detail(5L,"boss")).thenReturn(new AdminMember.Detail(summary("traveler01","ACTIVE","정상"),
            LocalDateTime.of(2026,9,10,0,0),true,null,
            List.of(new AdminMember.StatusChange(LocalDateTime.of(2026,9,5,1,0),"정상","이용 제한","약관 위반","boss"))));
        mvc.perform(get("/admin/members/5").with(user("boss").roles("ADMIN")))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("상태 변경")))
            .andExpect(content().string(containsString("약관 위반")))
            .andExpect(content().string(not(containsString("탈퇴 처리하기"))));
    }

    @Test void hidesFormForAdminAccountsAndReportsRejectedChange() throws Exception {
        when(service.detail(6L,"boss")).thenReturn(new AdminMember.Detail(summary("boss","ACTIVE","정상"),
            LocalDateTime.of(2026,9,10,0,0),false,"관리자 계정의 상태는 이 화면에서 바꿀 수 없어요.",List.of()));
        mvc.perform(get("/admin/members/6").with(user("boss").roles("ADMIN"))).andExpect(status().isOk())
            .andExpect(content().string(containsString("관리자 계정의 상태는")))
            .andExpect(content().string(not(containsString("변경 사유 (필수)"))));
        mvc.perform(post("/admin/members/5/status").with(user("boss").roles("ADMIN")).with(csrf())
                .param("status","RESTRICTED").param("reason","약관 위반"))
            .andExpect(redirectedUrl("/admin/members/5"))
            .andExpect(flash().attribute("adminMessage",containsString("기록했어요")));
        verify(service).changeStatus(5L,"RESTRICTED","약관 위반","boss");
        // 거절되면 회원 화면으로 돌아가 이유를 보이고, 적은 사유를 다시 채운다.
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT,"이미 같은 상태예요."))
            .when(service).changeStatus(eq(5L),anyString(),eq("다시 제한"),anyString());
        mvc.perform(post("/admin/members/5/status").with(user("boss").roles("ADMIN")).with(csrf())
                .param("status","RESTRICTED").param("reason","다시 제한"))
            .andExpect(redirectedUrl("/admin/members/5"))
            .andExpect(flash().attribute("statusError","이미 같은 상태예요."))
            .andExpect(flash().attribute("statusReason","다시 제한"));
        doThrow(new CannotCreateTransactionException("private details"))
            .when(service).changeStatus(eq(5L),anyString(),eq("연결 실패"),anyString());
        mvc.perform(post("/admin/members/5/status").with(user("boss").roles("ADMIN")).with(csrf())
                .param("status","RESTRICTED").param("reason","연결 실패"))
            .andExpect(redirectedUrl("/admin/members/5"))
            .andExpect(flash().attribute("statusError",containsString("잠시 후 다시 시도")));
        when(service.detail(5L,"boss")).thenReturn(new AdminMember.Detail(summary("traveler01","RESTRICTED","이용 제한"),
            LocalDateTime.of(2026,9,10,0,0),true,null,List.of()));
        mvc.perform(get("/admin/members/5").with(user("boss").roles("ADMIN"))
                .flashAttr("statusError","이미 같은 상태예요.").flashAttr("statusReason","다시 제한"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("이미 같은 상태예요.")))
            .andExpect(content().string(matchesRegex("(?s).*<textarea[^>]*>다시 제한</textarea>.*")));
    }

    @Test void databaseConnectionFailureShowsRetryPage() throws Exception {
        when(service.detail(5L,"boss")).thenThrow(new CannotCreateTransactionException("private details"));
        mvc.perform(get("/admin/members/5").with(user("boss").roles("ADMIN")))
            .andExpect(status().isServiceUnavailable())
            .andExpect(content().string(containsString("잠시 후 다시 시도")))
            .andExpect(content().string(not(containsString("private details"))));
    }
}
