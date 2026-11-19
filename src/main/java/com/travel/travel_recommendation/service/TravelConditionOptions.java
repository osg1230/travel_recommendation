package com.travel.travel_recommendation.service;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import com.travel.travel_recommendation.dto.TravelKeyword;
import com.travel.travel_recommendation.repository.KeywordRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** 요청마다 활성 선택지를 조회해 화면과 제출 검증에 같은 목록을 사용한다. */
@Component
public class TravelConditionOptions {
    public static final Map<String, String> CATEGORY_CODES = Map.of(
            "region", "REGION", "season", "SEASON", "companion", "COMPANION",
            "themes", "THEME", "transportation", "TRANSPORTATION",
            // 여행 분위기·속도(2026-11-16). 고르지 않아도 되는 선택 조건이다.
            "moods", "MOOD", "pace", "PACE");
    private final KeywordRepository repository;

    public TravelConditionOptions(ObjectProvider<KeywordRepository> repositories) {
        repository = repositories.getIfAvailable();
    }
    private final Map<String, List<String>> choices = Map.of(
            "region", List.of("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"),
            "season", List.of("봄", "여름", "가을", "겨울"),
            "companion", List.of("혼자", "친구", "연인", "가족"),
            "themes", List.of("자연", "바다", "휴식", "맛집", "문화·역사", "체험·액티비티"),
            "transportation", List.of("자차", "대중교통"),
            "moods", List.of("한적한", "활기찬", "감성적인"),
            "pace", List.of("느긋하게", "보통", "알차게"));

    public boolean isPreview() { return repository == null; }

    public Map<String, List<TravelKeyword>> getChoices() {
        Map<String, List<TravelKeyword>> result = new LinkedHashMap<>();
        CATEGORY_CODES.keySet().forEach(field -> result.put(field, new ArrayList<>()));
        if (isPreview()) {
            // DB 없는 기본 프로필의 화면 확인용 목록. 실제 DB ID로 취급하지 않는다.
            choices.forEach((field, names) -> names.forEach(name -> result.get(field)
                    .add(new TravelKeyword(null, CATEGORY_CODES.get(field), name, null))));
        } else {
            for (TravelKeyword keyword : repository.findActiveChoices()) {
                CATEGORY_CODES.forEach((field, code) -> {
                    if (code.equals(keyword.categoryCode())) result.get(field).add(keyword);
                });
            }
        }
        return result;
    }
}
