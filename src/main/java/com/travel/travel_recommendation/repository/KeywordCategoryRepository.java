package com.travel.travel_recommendation.repository;

import com.travel.travel_recommendation.entity.KeywordCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.repository.Repository;

// 관리자 키워드 관리에서 카테고리 이름·순서·사용 여부를 고칠 때 쓴다. 추가·삭제는 하지 않는다.
public interface KeywordCategoryRepository extends Repository<KeywordCategory, Long> {
    List<KeywordCategory> findAllByOrderBySortOrderAscIdAsc();
    Optional<KeywordCategory> findById(Long id);
    boolean existsByNameAndIdNot(String name, Long id);
    void flush();
}
