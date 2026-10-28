package com.travel.travel_recommendation.repository;

import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.entity.Keyword;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 조건 입력 화면의 선택지 조회와 관리자 키워드 관리(SFR-12)에 쓴다. 삭제 메서드는 두지 않고 사용 중지를 쓴다.
public interface KeywordRepository extends Repository<Keyword, Long> {
    @Query("""
        select new com.travel.travel_recommendation.dto.TravelKeyword(k.id, c.code, k.name, k.valueJson)
        from Keyword k join k.category c
        where k.active = true and c.active = true
        order by c.sortOrder, c.id, k.sortOrder, k.id
        """)
    List<TravelKeyword> findActiveChoices();

    /** 관리자 목록용. 사용 중지한 키워드와 카테고리도 함께 보여 준다. */
    @Query("select k from Keyword k join fetch k.category c order by c.sortOrder, c.id, k.sortOrder, k.id")
    List<Keyword> findAllWithCategory();

    @Query("select k from Keyword k join fetch k.category where k.id = :id")
    Optional<Keyword> findWithCategory(Long id);

    @Query("select coalesce(max(k.sortOrder), 0) from Keyword k where k.category.id = :categoryId")
    int findLastSortOrder(Long categoryId);

    long countByCategoryId(Long categoryId);
    boolean existsByCategoryIdAndCode(Long categoryId, String code);
    boolean existsByCategoryIdAndName(Long categoryId, String name);
    boolean existsByCategoryIdAndNameAndIdNot(Long categoryId, String name, Long id);
    Keyword save(Keyword keyword);
    void flush();
}
