package com.travel.travel_recommendation.service;

import java.util.*;

/**
 * 원하는 여행을 나타낼 때 쓰는 관광공사 새 분류체계(lclsSystm) 소분류 목록. GPT가 사용자의 여행 목적을 이 목록 안에서 고르고,
 * 서버는 여행지의 "할 수 있는 것" 목록({@link DestinationProfile})에서 같은 코드의 장소 수로 근거를 확인한다.
 * 음식·숙박·쇼핑몰·교통시설처럼 여행 목적으로 보기 어려운 분류는 넣지 않았다.
 */
public final class WishCategories {
    private WishCategories() {}

    /** 코드 → 이름(화면·GPT 입력용). */
    public static final Map<String, String> NAMES;
    static {
        Map<String, String> names = new LinkedHashMap<>();
        // 자연관광
        names.put("NA010100", "산·오름·봉우리"); names.put("NA010200", "숲"); names.put("NA010300", "폭포"); names.put("NA010400", "계곡");
        names.put("NA020100", "강"); names.put("NA020200", "호수"); names.put("NA020500", "섬"); names.put("NA020700", "항구·포구");
        names.put("NA020800", "해안절경"); names.put("NA020900", "해변·해수욕장"); names.put("NA030100", "동굴"); names.put("NA030400", "생태습지");
        names.put("NA040100", "국립공원"); names.put("NA040200", "도립공원"); names.put("NA040300", "군립공원"); names.put("NA040400", "지질공원");
        names.put("NA040600", "자연휴양림"); names.put("NA040700", "수목원·정원");
        // 역사관광
        names.put("HS010100", "고궁"); names.put("HS010200", "성·산성·성곽"); names.put("HS010400", "고택"); names.put("HS010600", "민속마을");
        names.put("HS010700", "사적지"); names.put("HS010800", "고분·능"); names.put("HS011100", "근대건축물"); names.put("HS030100", "사찰");
        // 문화관광
        names.put("VE010200", "타워·전망대"); names.put("VE020100", "테마파크"); names.put("VE020200", "워터파크"); names.put("VE020300", "동물원");
        names.put("VE020400", "수족관·아쿠아리움"); names.put("VE020500", "천문대"); names.put("VE040100", "골목길·문화거리"); names.put("VE040200", "마을관광지");
        names.put("VE040300", "둘레길"); names.put("VE070100", "박물관"); names.put("VE070300", "전시관"); names.put("VE070500", "과학관");
        names.put("VE070600", "미술관");
        // 체험관광
        names.put("EX010100", "전통문화체험"); names.put("EX020400", "공예체험"); names.put("EX030100", "체험마을"); names.put("EX030200", "체험목장");
        names.put("EX030300", "체험농장"); names.put("EX030400", "체험어장"); names.put("EX040100", "템플스테이"); names.put("EX050100", "온천·스파");
        names.put("EX070100", "유람선·잠수함");
        // 레저스포츠
        names.put("LS010200", "자전거"); names.put("LS010700", "승마"); names.put("LS010800", "스키·스노보드"); names.put("LS020100", "윈드서핑·제트스키");
        names.put("LS020200", "카약·카누"); names.put("LS020400", "스노클링·스쿠버다이빙"); names.put("LS020600", "바다낚시"); names.put("LS020800", "래프팅");
        names.put("LS030300", "패러글라이딩");
        // 쇼핑
        names.put("SH060200", "전통시장");
        NAMES = Collections.unmodifiableMap(names);
    }

    /** 목록에 있는 코드만 순서대로 중복 없이 남긴다. */
    public static List<String> known(Collection<String> codes) {
        return codes.stream().filter(NAMES::containsKey).distinct().toList();
    }

    /**
     * 소분류 하나가 가까운 여행 취향(조건 선택의 여행지 분류 이름). 위에서부터 먼저 맞는 것을 쓴다.
     * 예) 섬·해변 → 바다, 온천·휴양림 → 휴식, 산·계곡 → 자연, 사찰·박물관 → 문화·역사, 전통시장 → 맛집, 체험마을·수족관 → 체험·액티비티.
     */
    public static String theme(String code) {
        if (startsWithAny(code, "NA0205", "NA0207", "NA0208", "NA0209", "LS0201", "LS0204", "LS0206")) return "바다";
        if (startsWithAny(code, "EX0501", "EX0401", "NA0406")) return "휴식";
        if (startsWithAny(code, "NA", "VE0403")) return "자연";
        if (startsWithAny(code, "HS", "VE04", "VE07")) return "문화·역사";
        if (startsWithAny(code, "SH")) return "맛집";
        return "체험·액티비티";
    }

    /** 여행 취향 이름들에 해당하는 소분류 코드. 버튼으로 고른 취향을 여행지 근거와 맞출 때 쓴다. */
    public static List<String> codesFor(Collection<String> themes) {
        return NAMES.keySet().stream().filter(code -> themes.contains(theme(code))).toList();
    }

    private static boolean startsWithAny(String code, String... prefixes) {
        return Arrays.stream(prefixes).anyMatch(code::startsWith);
    }
}
