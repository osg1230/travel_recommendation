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
        /** 많이 고른 태그를 고른 사람 수와 함께 한 줄로 만든다(예: 경치가 좋아요 3명, 조용해요 2명). SFR-10-02 태그별 선택 횟수. */
        public String topTagText() {
            return String.join(", ", topTags.stream().map(t -> t.name() + " " + t.count() + "명").toList());
        }
    }
    /**
     * 평가할 장소 정보. mapUrl은 위치 보기 링크(지도 주소가 없으면 이름·주소 검색)이고 sourceUrl은 없을 수 있다.
     * active=false면 더 이상 확인되지 않는 장소라 새로 평가하거나 바꿀 수 없고 내 평가 삭제만 할 수 있다.
     */
    public record PlaceInfo(Long placeId, String name, String type, String typeName, String address,
                            String mapUrl, String sourceUrl, boolean active) {}
    /** 평가 입력 화면. mine이 없으면 새 평가, 있으면 갱신이다. droppedTags는 내 평가에 있지만 지금은 사용 중지돼 다시 저장하면 빠지는 태그 이름이다. */
    public record Form(PlaceInfo place, Mine mine, List<Tag> tags, Summary summary, List<String> droppedTags) {}
    public record Mine(int score, List<Long> tagIds, String comment, LocalDateTime updatedAt) {}
    /**
     * 평가 입력칸에 채울 값. 저장에 실패해 돌아왔으면 방금 적은 값, 아니면 내 기존 평가 값이다.
     * 저장 실패 뒤 다음 화면으로 넘길 때 세션(flash)에 잠시 담으므로 Serializable로 둔다.
     */
    public record Draft(Integer score, List<Long> tagIds, String comment) implements java.io.Serializable {
        public Draft {
            tagIds = tagIds == null ? List.of() : tagIds.stream().filter(Objects::nonNull).toList();
        }
    }
    public record MyRating(PlaceInfo place, int score, List<String> tags, String comment, LocalDateTime updatedAt) {}
    /** 평가 둘러보기 화면의 장소 한 곳: 장소 정보와 요약(최근 평가 포함). */
    public record RatedPlace(PlaceInfo place, Summary summary) {}
}
