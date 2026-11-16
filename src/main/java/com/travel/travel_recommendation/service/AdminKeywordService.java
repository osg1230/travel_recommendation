package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.AdminKeyword;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.*;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 관리자의 여행 조건 키워드·카테고리 관리(SFR-12). 저장된 추천 요청이 keyword_id를 참조하므로
 * 물리 삭제 대신 사용 중지를 쓰고, 코드·값은 바꾸지 않는다. 여행지·숙소·음식점 데이터는 다루지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class AdminKeywordService {
    private final KeywordRepository keywords;
    private final KeywordCategoryRepository categories;
    private final UserRepository users;

    // 이름·값을 추천 규칙이 그대로 쓰는 카테고리: 지역(시·도 검색), 계절(축제 계절 판단), 이동수단(경로 계산),
    // 여행 분위기·속도(장소 종류 점수와 하루 장소 수, 2026-11-16).
    static final Set<String> FIXED_CATEGORIES = Set.of("REGION","SEASON","TRANSPORTATION","MOOD","PACE");
    static final String FIXED_MESSAGE="지역·계절·이동수단·여행 분위기·여행 속도는 추천 규칙이 이름과 값을 그대로 쓰므로 이름을 바꾸거나 "
        +"새 키워드를 추가할 수 없어요. 표시 순서와 사용 여부만 바꿀 수 있어요.";
    private static final Pattern CODE=Pattern.compile("[A-Z][A-Z0-9_]{1,49}");
    static final int MAX_SORT_ORDER=999;

    @Transactional(readOnly=true,timeout=10)
    public List<AdminKeyword.Category> catalog(String adminLoginId) {
        admin(adminLoginId);
        // 키워드를 카테고리별로 묶는다. 키워드가 없는 카테고리도 목록에 보이도록 카테고리 기준으로 만든다.
        var grouped=new HashMap<Long,List<AdminKeyword.Item>>();
        for(var keyword:keywords.findAllWithCategory())
            grouped.computeIfAbsent(keyword.getCategory().getId(),id->new ArrayList<>()).add(item(keyword));
        return categories.findAllByOrderBySortOrderAscIdAsc().stream().map(c->new AdminKeyword.Category(
            c.getId(),c.getCode(),c.getName(),c.getSortOrder(),c.isActive(),addLockReason(c),
            grouped.getOrDefault(c.getId(),List.of()))).toList();
    }

    @Transactional(readOnly=true,timeout=10)
    public AdminKeyword.KeywordDetail keyword(Long keywordId, String adminLoginId) {
        admin(adminLoginId);
        var keyword=findKeyword(keywordId);
        return new AdminKeyword.KeywordDetail(item(keyword),keyword.getCategory().getName(),
            keyword.getCategory().isActive(),keyword.getUpdatedAt());
    }

    /** 동행자·여행 취향에만 추가한다. 표시 순서를 비우면 그 카테고리의 맨 뒤에 둔다. */
    @Transactional(timeout=10)
    public Long addKeyword(Long categoryId, String code, String name, String sortOrder, String adminLoginId) {
        admin(adminLoginId);
        if(categoryId==null) throw bad("키워드를 추가할 카테고리를 골라 주세요.");
        var category=findCategory(categoryId);
        String blocked=addLockReason(category);
        if(blocked!=null) throw bad(blocked);
        String keywordCode=code==null?"":code.strip().toUpperCase(Locale.ROOT);
        if(!CODE.matcher(keywordCode).matches())
            throw bad("코드는 영문으로 시작하는 영문·숫자·밑줄(_) 2~50자로 입력해 주세요.");
        String keywordName=name(name,80,"키워드 이름");
        int order=sortOrder==null || sortOrder.isBlank()
            ?Math.min(MAX_SORT_ORDER,keywords.findLastSortOrder(category.getId())+1):sortOrder(sortOrder);
        if(keywords.existsByCategoryIdAndCode(category.getId(),keywordCode))
            throw conflict("같은 카테고리에 이미 있는 코드예요. 다른 코드를 입력해 주세요.");
        if(keywords.existsByCategoryIdAndName(category.getId(),keywordName))
            throw conflict("같은 카테고리에 이미 있는 이름이에요. 사용 중지한 키워드라면 그 키워드를 다시 사용으로 바꿔 주세요.");
        var saved=keywords.save(new Keyword(category,keywordCode,keywordName,order));
        keywords.flush();
        return saved.getId();
    }

    /** 이름과 표시 순서를 고친다. 바뀐 내용이 없으면 false를 돌려준다. */
    @Transactional(timeout=10)
    public boolean updateKeyword(Long keywordId, String name, String sortOrder, String adminLoginId) {
        admin(adminLoginId);
        var keyword=findKeyword(keywordId);
        int order=sortOrder(sortOrder);
        String keywordName=keyword.getName();
        // 이름이 고정된 키워드는 화면에서 이름 칸을 보내지 않으므로 기존 이름을 그대로 쓴다.
        if(name!=null && !name.strip().equals(keyword.getName())) {
            String locked=nameLockReason(keyword);
            if(locked!=null) throw bad(locked);
            keywordName=name(name,80,"키워드 이름");
            if(keywords.existsByCategoryIdAndNameAndIdNot(keyword.getCategory().getId(),keywordName,keyword.getId()))
                throw conflict("같은 카테고리에 이미 있는 이름이에요. 다른 이름을 입력해 주세요.");
        }
        if(keywordName.equals(keyword.getName()) && order==keyword.getSortOrder()) return false;
        keyword.change(keywordName,order);
        keywords.flush();
        return true;
    }

    @Transactional(timeout=10)
    public void changeKeywordActive(Long keywordId, boolean active, String adminLoginId) {
        admin(adminLoginId);
        var keyword=findKeyword(keywordId);
        if(keyword.isActive()==active) throw conflict(active?"이미 사용 중인 키워드예요.":"이미 사용 중지한 키워드예요.");
        keyword.changeActive(active);
        keywords.flush();
    }

    @Transactional(readOnly=true,timeout=10)
    public AdminKeyword.CategoryDetail category(Long categoryId, String adminLoginId) {
        admin(adminLoginId);
        var category=findCategory(categoryId);
        return new AdminKeyword.CategoryDetail(category.getId(),category.getCode(),category.getName(),
            category.getSortOrder(),category.isActive(),keywords.countByCategoryId(category.getId()),category.getUpdatedAt());
    }

    /** 카테고리 이름과 표시 순서를 고친다. 코드는 조건 입력 화면의 질문과 연결돼 있어 바꾸지 않는다. */
    @Transactional(timeout=10)
    public boolean updateCategory(Long categoryId, String name, String sortOrder, String adminLoginId) {
        admin(adminLoginId);
        var category=findCategory(categoryId);
        String categoryName=name(name,50,"카테고리 이름");
        int order=sortOrder(sortOrder);
        if(categoryName.equals(category.getName()) && order==category.getSortOrder()) return false;
        if(categories.existsByNameAndIdNot(categoryName,category.getId()))
            throw conflict("이미 있는 카테고리 이름이에요. 다른 이름을 입력해 주세요.");
        category.change(categoryName,order);
        categories.flush();
        return true;
    }

    @Transactional(timeout=10)
    public void changeCategoryActive(Long categoryId, boolean active, String adminLoginId) {
        admin(adminLoginId);
        var category=findCategory(categoryId);
        if(category.isActive()==active) throw conflict(active?"이미 사용 중인 카테고리예요.":"이미 사용 중지한 카테고리예요.");
        category.changeActive(active);
        categories.flush();
    }

    /** 이름을 고칠 수 없으면 이유를, 고칠 수 있으면 null을 돌려준다. */
    private String nameLockReason(Keyword keyword) {
        String category=keyword.getCategory().getCode();
        if(FIXED_CATEGORIES.contains(category)) return FIXED_MESSAGE;
        // 기본 여행 취향 6개는 여행지 분류 점수와 취향 탐색이 이름·값으로 찾는다.
        if("THEME".equals(category) && PreferenceDiscovery.TAG_NAMES.containsKey(keyword.getCode()))
            return "기본 여행 취향은 여행지 분류와 취향 탐색이 이름을 그대로 쓰므로 이름은 바꿀 수 없어요. 표시 순서와 사용 여부만 바꿀 수 있어요.";
        return null;
    }
    /** 새 키워드를 추가할 수 없으면 이유를, 추가할 수 있으면 null을 돌려준다. */
    private String addLockReason(KeywordCategory category) {
        if(FIXED_CATEGORIES.contains(category.getCode())) return FIXED_MESSAGE;
        if(!TravelConditionOptions.CATEGORY_CODES.containsValue(category.getCode()))
            return "조건 입력 화면에 질문이 없는 카테고리라 키워드를 추가해도 화면에 나타나지 않아요.";
        return null;
    }
    private AdminKeyword.Item item(Keyword keyword) {
        return new AdminKeyword.Item(keyword.getId(),keyword.getCode(),keyword.getName(),keyword.getSortOrder(),
            keyword.isActive(),nameLockReason(keyword));
    }
    private Keyword findKeyword(Long keywordId) {
        return keywords.findWithCategory(keywordId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"키워드를 찾을 수 없어요."));
    }
    private KeywordCategory findCategory(Long categoryId) {
        return categories.findById(categoryId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"카테고리를 찾을 수 없어요."));
    }
    private String name(String value, int maxLength, String label) {
        String trimmed=value==null?"":value.strip();
        if(trimmed.isEmpty() || trimmed.length()>maxLength) throw bad(label+"을 1자 이상 "+maxLength+"자 이하로 입력해 주세요.");
        return trimmed;
    }
    private int sortOrder(String value) {
        try {
            int order=Integer.parseInt(value==null?"":value.strip());
            if(order>=0 && order<=MAX_SORT_ORDER) return order;
        } catch(NumberFormatException ignored) { }
        throw bad("표시 순서는 0~"+MAX_SORT_ORDER+" 사이의 정수로 입력해 주세요.");
    }
    // 화면 접근은 SecurityConfig가 막지만, 세션의 역할만 믿지 않고 실제 계정 권한을 다시 확인한다.
    private void admin(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        users.findByLoginId(loginId)
            .filter(u->u.getStatus()==UserStatus.ACTIVE && u.getRole()==UserRole.ADMIN)
            .orElseThrow(()->new AccessDeniedException("Admin required"));
    }
    private ResponseStatusException bad(String message) { return new ResponseStatusException(HttpStatus.BAD_REQUEST,message); }
    private ResponseStatusException conflict(String message) { return new ResponseStatusException(HttpStatus.CONFLICT,message); }
}
