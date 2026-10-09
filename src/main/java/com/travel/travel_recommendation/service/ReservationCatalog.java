package com.travel.travel_recommendation.service;

import java.util.Map;

/**
 * 가상 예약 수량(SQL 013 시드)이 있는 시스템 장소의 가격 출처와 객실당 최대 인원.
 * 수량·가격 자체는 reservation_stock에 있고, 여기에는 화면에 밝힐 출처와 인원 검증 기준만 둔다.
 */
public final class ReservationCatalog {
    private ReservationCatalog() {}

    public record Entry(int maxPerUnit, String priceNote, String sourceUrl) {}

    private static final String LODGING_NOTE =
        "체험용 객실·가격이에요. 여기어때 2026-11-18(수) 1박·2인 판매가(2026-09-22 확인)를 기준가로 삼아 객실 종류별로 조정했고, 실제 객실·요금과 달라요.";

    // 숙소의 객실당 최대 인원은 객실 종류(RoomType)가 정한다. 여기 값은 객실 종류가 없는 예전 수량 행에만 쓴다.
    private static final Map<String, Entry> ENTRIES = Map.of(
        "R1_GN_SEAMARQ", new Entry(2, LODGING_NOTE, "https://www.yeogi.com/domestic-accommodations/45239"),
        "R1_BS_SHILLA_STAY", new Entry(2, LODGING_NOTE, "https://www.yeogi.com/domestic-accommodations/45597"),
        "R1_GJ_LAHAN", new Entry(4, LODGING_NOTE, "https://www.yeogi.com/domestic-accommodations/6900"),
        "R1_GN_OJUKHEON", new Entry(1, "성인 개인 관람료예요(강릉시 오죽헌·시립박물관 관람안내, 2026-09-22 확인).",
            "https://www.gn.go.kr/museum/contents.do?key=891"),
        "R1_GJ_DONGGUNG", new Entry(1, "성인 개인 입장료예요(경주문화관광 입장료 안내 검색 결과, 2026-09-22 기준).",
            "https://www.gyeongju.go.kr/tour/page.do?mnu_uid=2378"));

    /** 출처가 없는 수량 행(서버가 만든 숙소 객실 등)은 체험용 가격으로만 표시하고 객실당 2인으로 검증한다. */
    public static Entry of(String sourcePlaceId, String unit) {
        var entry = sourcePlaceId == null ? null : ENTRIES.get(sourcePlaceId);
        if (entry != null) return entry;
        return "ROOM".equals(unit)
            ? new Entry(2, "체험용 객실·가격이에요. 실제 객실·요금과 관계없어요.", null)
            : new Entry(1, "체험용 가격이에요. 실제 입장료와 달라요.", null);
    }

    /**
     * 보통 입장권을 끊고 들어가는 관광시설의 체험용 1인 가격. 카카오 세부 분류(subtype)에 들어 있는 말로 정한다.
     * 해변·산책로·사찰·거리처럼 그냥 둘러보는 곳과 유료·무료가 섞인 문화유적은 대상이 아니라 null을 돌려준다.
     * 실제 입장료를 조회한 값이 아니다.
     */
    public static java.math.BigDecimal ticketPrice(String subtype) {
        if (subtype == null) return null;
        int won = subtype.contains("아쿠아리움") || subtype.contains("동물원") ? 25_000
            : subtype.contains("테마파크") ? 30_000
            : subtype.contains("케이블카") ? 15_000
            : subtype.contains("수목원") || subtype.contains("식물원") || subtype.contains("체험학습장") || subtype.contains("관광농원") ? 10_000
            : subtype.contains("박물관") || subtype.contains("전시관") || subtype.contains("고궁") ? 5_000
            : 0;
        return won == 0 ? null : java.math.BigDecimal.valueOf(won);
    }
}
