package com.travel.travel_recommendation.repository;

import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.entity.Keyword;
import java.util.List;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

// 선택지 조회만 제공한다. 관리자 등록·수정 기능은 이번 범위에 포함하지 않는다.
public interface KeywordRepository extends Repository<Keyword, Long> {
    @Query("""
        select new com.travel.travel_recommendation.dto.TravelKeyword(k.id, c.code, k.name, k.valueJson)
        from Keyword k join k.category c
        where k.active = true and c.active = true
        order by c.sortOrder, c.id, k.sortOrder, k.id
        """)
    List<TravelKeyword> findActiveChoices();
}
