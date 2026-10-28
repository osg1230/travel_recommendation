package com.travel.travel_recommendation.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

/** DB 없이 확인하는 한줄평 정리 규칙과 닉네임 가리기 규칙. */
class PlaceRatingServiceTests {
    @Test void trimsCommentAndTreatsBlankAsNone() {
        assertThat(PlaceRatingService.normalizeComment(null)).isNull();
        assertThat(PlaceRatingService.normalizeComment("")).isNull();
        assertThat(PlaceRatingService.normalizeComment("   \t ")).isNull();
        assertThat(PlaceRatingService.normalizeComment("  노을 무렵이 제일 예뻐요  ")).isEqualTo("노을 무렵이 제일 예뻐요");
    }

    @Test void rejectsCommentLongerThanLimit() {
        String limit="가".repeat(PlaceRatingService.MAX_COMMENT_LENGTH);
        assertThat(PlaceRatingService.normalizeComment(limit)).isEqualTo(limit);
        // 앞뒤 공백은 글자 수에 넣지 않는다.
        assertThat(PlaceRatingService.normalizeComment("  "+limit+"  ")).isEqualTo(limit);
        assertThatThrownBy(()->PlaceRatingService.normalizeComment(limit+"나"))
            .isInstanceOf(ResponseStatusException.class).hasMessageContaining("한줄평은 100자까지");
    }

    @Test void masksNicknameExceptFirstCharacter() {
        assertThat(PlaceRatingService.maskNickname("김여행")).isEqualTo("김**");
        assertThat(PlaceRatingService.maskNickname("여행_abcdef0123")).isEqualTo("여**");
        assertThat(PlaceRatingService.maskNickname(" 박 ")).isEqualTo("박**");
        // 이모지처럼 두 char로 된 글자도 한 글자로 남긴다.
        assertThat(PlaceRatingService.maskNickname("😀여행자")).isEqualTo("😀**");
        assertThat(PlaceRatingService.maskNickname(null)).isEqualTo("**");
        assertThat(PlaceRatingService.maskNickname("  ")).isEqualTo("**");
    }
}
