package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan;
import com.travel.travel_recommendation.dto.SavedItinerary;
import java.time.LocalTime;
import java.util.*;
import java.util.function.Predicate;

/**
 * 일정 수정 전과 후를 비교해 바뀐 관광지·식당·숙소를 찾는다(SFR-08-04).
 * 이동·자유 시간은 앞뒤 장소에 따라 저절로 바뀌므로 비교하지 않는다.
 * 같은 장소끼리 ① 같은 날 같은 시각이면 그대로 ② 같은 날 다른 시각이면 '시간 바뀜' ③ 다른 날이면 '날짜 바뀜'으로 짝을 짓고,
 * 짝이 없는 곳은 새로 넣은 곳이나 뺀 곳으로 본다.
 */
public final class ItineraryChanges {
    private ItineraryChanges() {}

    /** 비교할 일정 항목 하나. index는 그날 일정표에서의 순서(0부터)로, 화면 항목의 위치와 같다. */
    public record Stop(int day, int index, String type, String typeName, Long placeId, String name, LocalTime start) {
        // 장소 ID가 없는 항목(예전 저장 일정)은 이름으로 같은 곳인지 본다.
        String key() { return type+":"+(placeId!=null?placeId:name); }
        String title() { return typeName+" · "+name; }
    }
    /** 바뀐 내용 한 줄. kind(ADDED·REMOVED·TIME·MOVED)는 화면 배지 색을 정하고, day·start는 목록 정렬에 쓴다. */
    public record Change(String kind, String label, String text, int day, LocalTime start) {}
    /** 비교 결과. settings는 하루 시간·이동수단 같은 설정 변경, marks는 바뀐 뒤 일정의 '일차-순서' → 항목 옆 표시다. */
    public record Comparison(List<String> settings, List<Change> changes, Map<String,String> marks) {
        public boolean empty() { return settings.isEmpty() && changes.isEmpty(); }
        public String mark(int day, int index) { return marks.get(day+"-"+index); }
        /** 장소가 바뀐 줄(새로 넣음·뺌·날짜 바뀜). 화면에서 바로 보여 준다. */
        public List<Change> places() { return changes.stream().filter(c->!"TIME".equals(c.kind())).toList(); }
        /** 같은 날 방문 시각만 바뀐 줄. 앞 장소가 바뀌면 많이 생겨 화면에서 접어 둔다. */
        public List<Change> times() { return changes.stream().filter(c->"TIME".equals(c.kind())).toList(); }
    }

    public static Comparison compare(List<Stop> before, List<Stop> after, List<String> settings) {
        var left=new ArrayList<>(before.stream().filter(ItineraryChanges::compared).toList());
        var changes=new ArrayList<Change>();
        var marks=new LinkedHashMap<String,String>();
        // 1차: 같은 날 같은 시각에 그대로 있는 곳을 먼저 짝지어, 같은 장소가 여러 번 나와도 바뀌지 않은 쪽이 남지 않게 한다.
        var rest=new ArrayList<Stop>();
        for(var stop:after) {
            if(!compared(stop)) continue;
            if(take(left,s->s.key().equals(stop.key()) && s.day()==stop.day() && s.start().equals(stop.start()))==null) rest.add(stop);
        }
        for(var stop:rest) {
            var sameDay=take(left,s->s.key().equals(stop.key()) && s.day()==stop.day());
            if(sameDay!=null) {
                changes.add(new Change("TIME","시간 바뀜",stop.day()+"일차 "+stop.title()+" "+time(sameDay.start())+" → "+time(stop.start()),stop.day(),stop.start()));
                marks.put(stop.day()+"-"+stop.index(),"시간 바뀜 · 원래 "+time(sameDay.start()));
                continue;
            }
            var otherDay=take(left,s->s.key().equals(stop.key()));
            if(otherDay!=null) {
                changes.add(new Change("MOVED","날짜 바뀜",stop.title()+" "+otherDay.day()+"일차 "+time(otherDay.start())
                    +" → "+stop.day()+"일차 "+time(stop.start()),stop.day(),stop.start()));
                marks.put(stop.day()+"-"+stop.index(),"날짜 바뀜 · 원래 "+otherDay.day()+"일차");
                continue;
            }
            changes.add(new Change("ADDED","새로 넣음",stop.day()+"일차 "+time(stop.start())+" "+stop.title(),stop.day(),stop.start()));
            marks.put(stop.day()+"-"+stop.index(),"새로 넣음");
        }
        for(var stop:left)
            changes.add(new Change("REMOVED","뺌",stop.day()+"일차 "+time(stop.start())+" "+stop.title(),stop.day(),stop.start()));
        // 일정표를 읽는 순서(일차·시각)대로 보여 준다.
        changes.sort(Comparator.comparingInt(Change::day).thenComparing(Change::start));
        return new Comparison(List.copyOf(settings),List.copyOf(changes),Map.copyOf(marks));
    }

    /** 두 값이 다르면 '이름: 전 → 후' 한 줄을 더한다. 어느 한쪽을 모르면(예전 저장 일정) 비교하지 않는다. */
    public static void setting(List<String> settings, String name, String before, String after) {
        if(before!=null && after!=null && !before.equals(after)) settings.add(name+": "+before+" → "+after);
    }

    /** 일정 초안(작업 결과)의 항목. 종류 이름은 초안 화면과 같은 규칙으로 정한다. */
    public static List<Stop> fromPlan(List<ItineraryPlan.Day> days) {
        var stops=new ArrayList<Stop>();
        for(var day:days) for(int i=0;i<day.items().size();i++) {
            var item=day.items().get(i);
            stops.add(new Stop(day.number(),i,item.type(),typeName(item.type(),item.note()),item.placeId(),item.name(),item.start()));
        }
        return stops;
    }
    /** 저장한 일정의 항목. */
    public static List<Stop> fromSaved(List<SavedItinerary.Day> days) {
        var stops=new ArrayList<Stop>();
        for(var day:days) for(int i=0;i<day.items().size();i++) {
            var item=day.items().get(i);
            stops.add(new Stop(day.number(),i,item.type(),item.typeName(),item.placeId(),item.name(),item.start()));
        }
        return stops;
    }

    private static boolean compared(Stop stop) {
        return Set.of("VISIT","MEAL","STAY").contains(stop.type()) && stop.start()!=null;
    }
    // 조건에 맞는 첫 항목을 목록에서 꺼낸다. 없으면 null이다.
    private static Stop take(List<Stop> stops, Predicate<Stop> match) {
        for(var it=stops.iterator();it.hasNext();) {
            var stop=it.next();
            if(match.test(stop)) { it.remove(); return stop; }
        }
        return null;
    }
    private static String typeName(String type, String note) {
        return switch(type) {
            case "VISIT" -> note!=null && note.startsWith("저녁 관광")?"저녁 관광":"관광";
            case "MEAL" -> "식사";
            case "STAY" -> "숙소";
            default -> type;
        };
    }
    private static String time(LocalTime time) {
        return String.format("%02d:%02d",time.getHour(),time.getMinute());
    }
}
