package com.travel.travel_recommendation;

import com.travel.travel_recommendation.dto.ItineraryPlan;
import com.travel.travel_recommendation.service.ItineraryChanges;
import com.travel.travel_recommendation.service.ItineraryChanges.Stop;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

/** 일정 수정 전후 비교(SFR-08-04): 새로 넣음·뺌·시간 바뀜·날짜 바뀜과 설정 변경을 찾는다. */
class ItineraryChangesTests {
    private static Stop stop(int day, int index, String type, long placeId, String name, int hour) {
        String typeName=switch(type) { case "VISIT" -> "관광"; case "MEAL" -> "식사"; default -> "숙소"; };
        return new Stop(day,index,type,typeName,placeId,name,LocalTime.of(hour,0));
    }

    @Test void findsAddedRemovedTimeAndDayChangesInScheduleOrder() {
        var before=List.of(stop(1,0,"VISIT",1,"경포대",10),stop(1,2,"MEAL",2,"초당순두부",12),stop(1,4,"VISIT",3,"오죽헌",14),
            stop(1,6,"STAY",9,"호텔",18),stop(2,0,"VISIT",4,"안목해변",10));
        var after=List.of(stop(1,0,"VISIT",1,"경포대",10),stop(1,2,"MEAL",2,"초당순두부",13),stop(1,4,"VISIT",5,"주문진",15),
            stop(1,6,"STAY",9,"호텔",18),stop(2,0,"VISIT",3,"오죽헌",10));
        var settings=new ArrayList<String>();
        ItineraryChanges.setting(settings,"이동수단","대중교통","자동차");
        ItineraryChanges.setting(settings,"하루 일정 시간","09:00~18:00","09:00~18:00");
        // 한쪽 값을 모르면(예전 저장 일정) 비교하지 않는다.
        ItineraryChanges.setting(settings,"관광지 체류시간",null,"90분");

        var result=ItineraryChanges.compare(before,after,settings);

        assertThat(result.settings()).containsExactly("이동수단: 대중교통 → 자동차");
        assertThat(result.changes()).extracting(ItineraryChanges.Change::text).containsExactly(
            "1일차 식사 · 초당순두부 12:00 → 13:00",
            "1일차 15:00 관광 · 주문진",
            "관광 · 오죽헌 1일차 14:00 → 2일차 10:00",
            "2일차 10:00 관광 · 안목해변");
        assertThat(result.changes()).extracting(ItineraryChanges.Change::kind).containsExactly("TIME","ADDED","MOVED","REMOVED");
        // 화면은 장소 변경을 먼저 보여 주고 시각만 바뀐 곳은 따로 접어 둔다.
        assertThat(result.places()).extracting(ItineraryChanges.Change::kind).containsExactly("ADDED","MOVED","REMOVED");
        assertThat(result.times()).hasSize(1);
        assertThat(result.mark(1,2)).isEqualTo("시간 바뀜 · 원래 12:00");
        assertThat(result.mark(1,4)).isEqualTo("새로 넣음");
        assertThat(result.mark(2,0)).isEqualTo("날짜 바뀜 · 원래 1일차");
        assertThat(result.mark(1,0)).isNull();
        assertThat(result.mark(1,6)).isNull();
    }

    @Test void repeatedPlaceMarksOnlyChangedNightAndSkipsTransit() {
        // 같은 숙소가 두 밤 나오고 둘째 밤만 시각이 바뀌면 그 밤만 표시한다. 이동 항목은 비교하지 않는다.
        var before=List.of(stop(1,3,"STAY",9,"호텔",18),stop(2,3,"STAY",9,"호텔",18),
            new Stop(1,1,"TRANSIT","이동",null,"이동",LocalTime.of(11,0)));
        var after=List.of(stop(1,3,"STAY",9,"호텔",18),stop(2,3,"STAY",9,"호텔",19));

        var result=ItineraryChanges.compare(before,after,List.of());

        assertThat(result.changes()).extracting(ItineraryChanges.Change::text).containsExactly("2일차 숙소 · 호텔 18:00 → 19:00");
        assertThat(result.marks()).containsOnlyKeys("2-3");
    }

    @Test void sameScheduleHasNoChanges() {
        var stops=List.of(stop(1,0,"VISIT",1,"경포대",10),stop(1,2,"MEAL",2,"초당순두부",12));
        assertThat(ItineraryChanges.compare(stops,stops,List.of()).empty()).isTrue();
    }

    @Test void draftStopsUseScreenTypeNames() {
        var day=new ItineraryPlan.Day(1,LocalDate.of(2026,11,1),List.of(
            new ItineraryPlan.Item("VISIT",1L,"송정해변",LocalTime.of(19,0),LocalTime.of(20,0),"저녁 관광 · 숙소 근처",null,null,null,null),
            new ItineraryPlan.Item("MEAL",2L,"초당순두부",LocalTime.of(12,0),LocalTime.of(13,0),"",null,null,null,null)));
        assertThat(ItineraryChanges.fromPlan(List.of(day))).extracting(Stop::typeName).containsExactly("저녁 관광","식사");
        assertThat(ItineraryChanges.fromPlan(List.of(day))).extracting(Stop::index).containsExactly(0,1);
    }
}
