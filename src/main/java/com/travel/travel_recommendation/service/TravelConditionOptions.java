package com.travel.travel_recommendation.service;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** 입력 단계의 선택지. DB 키워드 연결 전까지 화면과 서버가 같은 목록을 사용한다. */
@Component
public class TravelConditionOptions {
    private final Map<String, List<String>> choices = Map.of(
            "region", List.of("서울", "부산", "대구", "인천", "광주", "대전", "울산", "세종", "경기", "강원", "충북", "충남", "전북", "전남", "경북", "경남", "제주"),
            "season", List.of("봄", "여름", "가을", "겨울"),
            "companion", List.of("혼자", "친구", "연인", "가족"),
            "themes", List.of("자연", "바다", "휴식", "맛집", "문화·역사", "체험·액티비티"),
            "transportation", List.of("자차", "대중교통"));

    public Map<String, List<String>> getChoices() { return choices; }

    public boolean contains(String field, String value) {
        return value != null && choices.get(field).contains(value);
    }
}
