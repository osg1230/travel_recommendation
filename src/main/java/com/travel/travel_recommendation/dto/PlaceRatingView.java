package com.travel.travel_recommendation.dto;

import java.time.LocalDateTime;
import java.util.*;

/** 장소 평가 화면·요약용 데이터(SFR-10). 다른 회원에게는 가린 닉네임(예: 김**)만 보여 주고 로그인 ID 같은 식별정보는 넣지 않는다. */
public final class PlaceRatingView {
    private PlaceRatingView() {}
    public record Tag(Long id, String name) {}
    public record TagCount(String name, long count) {}
    /** 다른 회원의 최근 평가 한 건. raterName은 가린 닉네임이고 comment는 한줄평이 없으면 null이다. */
    public record Recent(String raterName, int score, List<String> tags, String comment, LocalDateTime updatedAt) {}
    /** average는 소수 첫째 자리로 반올림한다. 평가가 없으면 count=0, average=null이다. recent는 최근에 바꾼 순서의 평가 최대 5건이다. */
    public record Summary(Double average, long count, List<TagCount> topTags, List<Recent> recent) {
        public static final Summary EMPTY = new Summary(null, 0, List.of(), List.of());
    }
    public record PlaceInfo(Long placeId, String name, String type, String typeName, String address) {}
    /** 평가 입력 화면. mine이 없으면 새 평가, 있으면 갱신이다. */
    public record Form(PlaceInfo place, Mine mine, List<Tag> tags, Summary summary) {}
    public record Mine(int score, List<Long> tagIds, String comment, LocalDateTime updatedAt) {}
    public record MyRating(PlaceInfo place, int score, List<String> tags, String comment, LocalDateTime updatedAt) {}
    /** 평가 둘러보기 화면의 장소 한 곳: 장소 정보와 요약(최근 평가 포함). */
    public record RatedPlace(PlaceInfo place, Summary summary) {}
}
