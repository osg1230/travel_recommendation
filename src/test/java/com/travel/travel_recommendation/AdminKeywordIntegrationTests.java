package com.travel.travel_recommendation;

import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import com.travel.travel_recommendation.service.AdminKeywordService;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.hamcrest.Matchers.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 관리자 키워드 관리가 실제 SQL Server와 조건 입력 화면의 선택지에 반영되는지 확인한다. 변경은 테스트 종료 시 롤백한다. */
@EnabledIfEnvironmentVariable(named="RUN_SQLSERVER_TESTS",matches="true")
@SpringBootTest(properties={"spring.jpa.hibernate.ddl-auto=validate","travel.jobs.enabled=false"})
@AutoConfigureMockMvc
@ActiveProfiles("sqlserver-windows")
@Transactional
class AdminKeywordIntegrationTests {
    @Autowired AdminKeywordService service;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired MockMvc mvc;
    private User admin;
    private String suffix;

    @BeforeEach void setup() {
        suffix=UUID.randomUUID().toString().replace("-","").substring(0,8).toUpperCase();
        var user=users.saveAndFlush(new User("test_kw_"+suffix.toLowerCase(),encoder.encode("Travel123!"),"키워드_"+suffix));
        jdbc.update("UPDATE dbo.users SET role='ADMIN' WHERE user_id=?",user.getId());
        em.flush(); em.clear();
        admin=users.findById(user.getId()).orElseThrow();
    }
    private RequestPostProcessor asAdmin() { return user(admin.getLoginId()).roles("ADMIN"); }
    private Long categoryId(String code) {
        return jdbc.queryForObject("SELECT category_id FROM dbo.keyword_categories WHERE category_code=?",Long.class,code);
    }
    private Long keywordId(String categoryCode, String keywordCode) {
        return jdbc.queryForObject("""
            SELECT k.keyword_id FROM dbo.keywords k JOIN dbo.keyword_categories c ON c.category_id=k.category_id
            WHERE c.category_code=? AND k.keyword_code=?""",Long.class,categoryCode,keywordCode);
    }
    // 조건 입력 화면에서 해당 키워드의 선택 칸이 그려졌는지 본다.
    private String conditionScreen() throws Exception {
        return mvc.perform(get("/travel/conditions")).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private static String choiceOf(Long keywordId) { return "value=\""+keywordId+"\""; }

    @Test void addedRenamedAndStoppedKeywordsAppearOnConditionScreen() throws Exception {
        String code="TEST_"+suffix, name="관리자확인"+suffix;
        mvc.perform(post("/admin/keywords").with(asAdmin()).with(csrf())
                .param("categoryId",categoryId("THEME").toString()).param("code",code.toLowerCase()).param("name",name))
            .andExpect(status().is3xxRedirection());
        Long id=keywordId("THEME",code);
        assertThat(jdbc.queryForObject("SELECT value_json FROM dbo.keywords WHERE keyword_id=?",String.class,id))
            .isEqualTo("{\"value\":\""+code+"\"}");
        assertThat(conditionScreen()).contains(choiceOf(id)).contains(name);

        mvc.perform(post("/admin/keywords/"+id).with(asAdmin()).with(csrf()).param("name",name+"밤").param("sortOrder","0"))
            .andExpect(redirectedUrl("/admin/keywords/"+id));
        em.clear();
        assertThat(conditionScreen()).contains(name+"밤");
        assertThat(jdbc.queryForObject("SELECT keyword_code FROM dbo.keywords WHERE keyword_id=?",String.class,id)).isEqualTo(code);

        mvc.perform(post("/admin/keywords/"+id+"/active").with(asAdmin()).with(csrf()).param("active","false"))
            .andExpect(redirectedUrl("/admin/keywords/"+id));
        em.clear();
        assertThat(conditionScreen()).doesNotContain(choiceOf(id)).doesNotContain(name+"밤");
        // 사용 중지한 키워드도 관리자 목록에는 남아 다시 사용할 수 있다.
        mvc.perform(get("/admin/keywords").with(asAdmin())).andExpect(content().string(containsString(name+"밤")));
        service.changeKeywordActive(id,true,admin.getLoginId());
        em.clear();
        assertThat(conditionScreen()).contains(choiceOf(id));
    }

    @Test void protectsRuleLinkedKeywordsButAllowsOrderAndActiveChanges() throws Exception {
        Long seoul=keywordId("REGION","SEOUL");
        assertThatThrownBy(()->service.addKeyword(categoryId("REGION"),"TEST_"+suffix,"테스트시","",admin.getLoginId()))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.updateKeyword(seoul,"서울특별시","1",admin.getLoginId()))
            .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(()->service.updateKeyword(keywordId("THEME","SEA"),"바닷가","2",admin.getLoginId()))
            .isInstanceOf(ResponseStatusException.class);
        // 같은 카테고리의 중복 이름은 DB 제약 전에 서비스에서 막는다.
        assertThatThrownBy(()->service.addKeyword(categoryId("THEME"),"TEST_"+suffix,"바다","",admin.getLoginId()))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 있는 이름");

        // 순서는 바꿀 수 있고 조건 입력 화면의 지역 순서에도 반영된다.
        mvc.perform(post("/admin/keywords/"+seoul).with(asAdmin()).with(csrf()).param("sortOrder","999"))
            .andExpect(redirectedUrl("/admin/keywords/"+seoul));
        em.clear();
        String screen=conditionScreen();
        assertThat(screen.indexOf(choiceOf(seoul))).isGreaterThan(screen.indexOf(choiceOf(keywordId("REGION","JEJU"))));
        assertThat(jdbc.queryForObject("SELECT keyword_name FROM dbo.keywords WHERE keyword_id=?",String.class,seoul)).isEqualTo("서울");
    }

    @Test void stoppedCategoryHidesItsKeywordsUntilReactivated() throws Exception {
        Long theme=categoryId("THEME"), sea=keywordId("THEME","SEA");
        mvc.perform(post("/admin/keywords/categories/"+theme+"/active").with(asAdmin()).with(csrf()).param("active","false"))
            .andExpect(redirectedUrl("/admin/keywords/categories/"+theme));
        em.clear();
        assertThat(conditionScreen()).doesNotContain(choiceOf(sea));
        mvc.perform(post("/admin/keywords/categories/"+theme).with(asAdmin()).with(csrf())
                .param("name","취향 "+suffix).param("sortOrder","4"))
            .andExpect(redirectedUrl("/admin/keywords/categories/"+theme));
        service.changeCategoryActive(theme,true,admin.getLoginId());
        em.clear();
        assertThat(conditionScreen()).contains(choiceOf(sea));
        assertThat(jdbc.queryForObject("SELECT category_code FROM dbo.keyword_categories WHERE category_id=?",String.class,theme))
            .isEqualTo("THEME");
    }

    @Test void refusesAccountsWithoutRealAdminRole() throws Exception {
        var member=users.saveAndFlush(new User("test_km_"+suffix.toLowerCase(),encoder.encode("Travel123!"),"회원_"+suffix));
        // 일반 회원이 ADMIN 역할을 흉내 내도 서버가 실제 계정 권한을 다시 확인한다.
        mvc.perform(get("/admin/keywords").with(user(member.getLoginId()).roles("ADMIN"))).andExpect(status().isForbidden());
        mvc.perform(get("/admin/keywords").with(asAdmin())).andExpect(status().isOk())
            .andExpect(content().string(containsString("키워드 관리")));
    }
}
