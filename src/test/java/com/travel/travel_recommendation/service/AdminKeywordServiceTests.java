package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 관리자 키워드 관리의 보호 규칙과 입력 검증을 DB 없이 확인한다. */
class AdminKeywordServiceTests {
    private final KeywordRepository keywords=mock(KeywordRepository.class);
    private final KeywordCategoryRepository categories=mock(KeywordCategoryRepository.class);
    private final UserRepository users=mock(UserRepository.class);
    private final AdminKeywordService service=new AdminKeywordService(keywords,categories,users);
    private final KeywordCategory region=category(1L,"REGION","지역");
    private final KeywordCategory theme=category(4L,"THEME","여행 취향");

    private static KeywordCategory category(Long id, String code, String name) {
        var category=mock(KeywordCategory.class);
        when(category.getId()).thenReturn(id);
        when(category.getCode()).thenReturn(code);
        when(category.getName()).thenReturn(name);
        when(category.isActive()).thenReturn(true);
        return category;
    }

    @BeforeEach void admin() {
        var admin=mock(User.class);
        when(admin.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(admin.getRole()).thenReturn(UserRole.ADMIN);
        when(users.findByLoginId("boss")).thenReturn(Optional.of(admin));
        var member=mock(User.class);
        when(member.getStatus()).thenReturn(UserStatus.ACTIVE);
        when(member.getRole()).thenReturn(UserRole.MEMBER);
        when(users.findByLoginId("member")).thenReturn(Optional.of(member));
        when(categories.findById(1L)).thenReturn(Optional.of(region));
        when(categories.findById(4L)).thenReturn(Optional.of(theme));
    }

    @Test void addsThemeKeywordWithCodeValueAndPutsItLastWhenOrderIsBlank() {
        when(keywords.findLastSortOrder(4L)).thenReturn(6);
        when(keywords.save(any())).thenAnswer(call->call.getArgument(0));
        service.addKeyword(4L," night_view ","  야경 ","","boss");
        verify(keywords).save(argThat(k->k.getCode().equals("NIGHT_VIEW") && k.getName().equals("야경")
            && k.getValueJson().equals("{\"value\":\"NIGHT_VIEW\"}") && k.getSortOrder()==7 && k.isActive()));
    }

    @Test void refusesAddingToRuleLinkedCategoriesAndBadInput() {
        assertThatThrownBy(()->service.addKeyword(1L,"MOKPO","목포","","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("지역·계절·이동수단");
        assertThatThrownBy(()->service.addKeyword(null,"NIGHT","야경","","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("카테고리를 골라");
        for(String code:List.of("","1NIGHT","야경","N","NIGHT-VIEW"))
            assertThatThrownBy(()->service.addKeyword(4L,code,"야경","","boss"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("코드는");
        assertThatThrownBy(()->service.addKeyword(4L,"NIGHT","  ","","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("키워드 이름을");
        for(String order:List.of("-1","1000","첫째"))
            assertThatThrownBy(()->service.addKeyword(4L,"NIGHT","야경",order,"boss"))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("표시 순서는");
        when(keywords.existsByCategoryIdAndName(4L,"바다")).thenReturn(true);
        assertThatThrownBy(()->service.addKeyword(4L,"SEA2","바다","3","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 있는 이름");
        verify(keywords,never()).save(any());
    }

    @Test void locksNamesThatRecommendationRulesUseButKeepsOrderEditable() {
        var seoul=new Keyword(region,"SEOUL","서울",1);
        var sea=new Keyword(theme,"SEA","바다",2);
        when(keywords.findWithCategory(11L)).thenReturn(Optional.of(seoul));
        when(keywords.findWithCategory(12L)).thenReturn(Optional.of(sea));
        assertThatThrownBy(()->service.updateKeyword(11L,"서울특별시","1","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이름을 바꾸거나");
        assertThatThrownBy(()->service.updateKeyword(12L,"바닷가","2","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("기본 여행 취향");
        // 이름 칸이 없는 화면에서는 name 없이 순서만 보낸다.
        assertThat(service.updateKeyword(11L,null,"30","boss")).isTrue();
        assertThat(seoul.getName()).isEqualTo("서울");
        assertThat(seoul.getSortOrder()).isEqualTo(30);
        assertThat(service.updateKeyword(12L,"바다","2","boss")).isFalse();
    }

    @Test void renamesAddedThemeAndChecksDuplicatesAndActiveState() {
        var nightView=new Keyword(theme,"NIGHT_VIEW","야경",7);
        when(keywords.findWithCategory(40L)).thenReturn(Optional.of(nightView));
        when(keywords.existsByCategoryIdAndNameAndIdNot(4L,"바다",null)).thenReturn(true);
        assertThatThrownBy(()->service.updateKeyword(40L,"바다","7","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 있는 이름");
        assertThat(service.updateKeyword(40L," 밤 풍경 ","8","boss")).isTrue();
        assertThat(nightView.getName()).isEqualTo("밤 풍경");
        assertThat(nightView.getCode()).isEqualTo("NIGHT_VIEW");
        service.changeKeywordActive(40L,false,"boss");
        assertThat(nightView.isActive()).isFalse();
        assertThatThrownBy(()->service.changeKeywordActive(40L,false,"boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 사용 중지");
    }

    @Test void editsCategoryNameAndOrderOnly() {
        assertThatThrownBy(()->service.updateCategory(4L,"","1","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("카테고리 이름을");
        when(categories.existsByNameAndIdNot("지역",4L)).thenReturn(true);
        assertThatThrownBy(()->service.updateCategory(4L,"지역","4","boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 있는 카테고리 이름");
        assertThat(service.updateCategory(4L,"취향","4","boss")).isTrue();
        verify(theme).change("취향",4);
        assertThatThrownBy(()->service.changeCategoryActive(4L,true,"boss"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("이미 사용 중인");
    }

    @Test void refusesAccountsThatAreNotActiveAdmins() {
        assertThatThrownBy(()->service.catalog("member")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.addKeyword(4L,"NIGHT","야경","","member")).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->service.catalog(null)).isInstanceOf(AccessDeniedException.class);
        verifyNoInteractions(keywords);
    }
}
