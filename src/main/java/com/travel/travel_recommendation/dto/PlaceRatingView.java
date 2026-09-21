package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;
import java.util.*;

/** 장소 평가 화면·요약용 데이터(SFR-10). 평가자 식별정보는 요약에 넣지 않는다. */
public final class PlaceRatingView {
    private PlaceRatingView() {}
    public record Tag(Long id, String name) {}
    public record TagCount(String name, long count) {}
    /** average는 소수 첫째 자리로 반올림한다. 평가가 없으면 count=0, average=null이다. */
    public record Summary(Double average, long count, List<TagCount> topTags) {
        public static final Summary EMPTY = new Summary(null, 0, List.of());
    }
    public record PlaceInfo(Long placeId, String name, String type, String typeName, String address) {}
    /** 평가 입력 화면. mine이 없으면 새 평가, 있으면 갱신이다. */
    public record Form(PlaceInfo place, Mine mine, List<Tag> tags, Summary summary) {}
    public record Mine(int score, List<Long> tagIds, LocalDateTime updatedAt) {}
    public record MyRating(PlaceInfo place, int score, List<String> tags, LocalDateTime updatedAt) {}
}
