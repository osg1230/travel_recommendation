package com.travel.travel_recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReadableTextTests {
    @Test void durationShowsHoursAndMinutes() {
        assertThat(ReadableText.duration(45)).isEqualTo("45분");
        assertThat(ReadableText.duration(120)).isEqualTo("2시간");
        assertThat(ReadableText.duration(150)).isEqualTo("2시간 30분");
    }

    @Test void particleFollowsFinalConsonantOfTheLastSpokenLetter() {
        assertThat(ReadableText.particle("소돌해안일주 산책로", "은")).isEqualTo("는");
        assertThat(ReadableText.particle("전동성당", "은")).isEqualTo("은");
        // 닫는 괄호는 건너뛰고 그 앞 글자('간')의 받침을 본다.
        assertThat(ReadableText.particle("해파랑길 38코스(바우길06구간)", "을")).isEqualTo("을");
        assertThat(ReadableText.particle("경포대", "을")).isEqualTo("를");
        // 숫자는 읽는 소리로 본다: 2(이)는 받침이 없고 3(삼)은 있다.
        assertThat(ReadableText.particle("코스 2", "은")).isEqualTo("는");
        assertThat(ReadableText.particle("코스 3", "은")).isEqualTo("은");
    }
}
