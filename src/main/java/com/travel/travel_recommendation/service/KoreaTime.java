package com.travel.travel_recommendation.service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 서비스가 DB에 저장하는 시각의 기준(한국 시각, Asia/Seoul).
 * 생성·수정·처리 시각은 이 기준으로 저장하고 화면에도 그대로 보여 준다(2026-10-10, SQL 017 전까지는 UTC였다).
 * DB 기본값 SYSDATETIME()과 같은 기준이어야 자바가 넣은 값과 DB가 넣은 값이 섞여도 어긋나지 않는다.
 */
public final class KoreaTime {
    public static final ZoneId ZONE = ZoneId.of("Asia/Seoul");

    private KoreaTime() {}

    /** 현재 한국 시각. DB의 datetime2 컬럼에 저장하는 값은 모두 이 메서드로 만든다. */
    public static LocalDateTime now() {
        return LocalDateTime.now(ZONE);
    }
}
