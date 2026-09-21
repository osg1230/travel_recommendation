package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class ItineraryGenerator {
    private final KakaoMapClient maps;
    private static final Pattern ROAD=Pattern.compile("^(.+?(?:로|길)\\s+\\d+(?:-\\d+)?)(?:\\s+.*)?$");

    public Result generate(Input input) {
        checkInput(input);
        Instant deadline=Instant.now().plusSeconds(110);
        Map<Long,KakaoMapClient.Location> locations=new HashMap<>();
        Map<String,KakaoMapClient.Location> addresses=new HashMap<>();
        var places=input.places();
        var hotel=places.accommodationNeeded()?places.accommodations().getFirst():null;
        var used=new ArrayList<>(places.attractions()); used.addAll(places.restaurants()); if(hotel!=null) used.add(hotel);
        for(var place:used) {
            checkDeadline(deadline);
            if("KAKAO".equals(place.sourceProvider()) && place.longitude()!=null && place.latitude()!=null) {
                if(place.verifiedAt()==null || place.verifiedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusHours(24)))
                    throw failure("PLACE_STALE","장소 조회 결과가 오래됐어요. 장소 목록에서 다시 조회해 주세요.");
                locations.put(place.placeId(),new KakaoMapClient.Location(place.address(),place.longitude(),place.latitude(),place.verifiedAt().toInstant(ZoneOffset.UTC)));
                continue;
            }
            var matcher=ROAD.matcher(place.address());
            if(!matcher.matches()) throw failure("ADDRESS_REQUIRED","장소의 도로명 주소를 확인해야 해요.");
            String address=matcher.group(1);
            // 같은 건물의 음식점·숙소는 주소 좌표 조회를 한 번만 한다.
            locations.put(place.placeId(),addresses.computeIfAbsent(address,maps::locate));
        }
        var remaining=new ArrayList<>(places.attractions());
        List<Day> days=new ArrayList<>();
        Map<String,KakaoMapClient.TravelTime> routes=new HashMap<>();
        PlaceCandidate previous=null;
        int dayCount=input.conditions().durationDays();
        for(int day=1;day<=dayCount;day++) {
            var builder=new DayBuilder(input,locations,routes,deadline,previous,day);
            int count=(remaining.size()+dayCount-day)/(dayCount-day+1);
            // 가까운 관광지를 연속 선택한 뒤 하루 단위로 나눈다. 최단 경로 보장은 아니다.
            for(int i=0;i<count;i++) {
                PlaceCandidate anchor=builder.previous;
                var next=remaining.stream().min(order(builder.date,p->anchor==null?remaining.indexOf(p)
                    :distance(locations.get(anchor.placeId()),locations.get(p.placeId())))).orElseThrow();
                remaining.remove(next);
                builder.visit(next,"VISIT",input.visitMinutes(),"관광·관람 (계획한 체류시간)");
                if(!builder.meal && (!builder.time.isBefore(LocalTime.NOON) || i==count-1)) {
                    // 식사는 방금 방문한 관광지에서 가까운 후보를 고른다.
                    var restaurant=places.restaurants().stream().min(order(builder.date,
                        p->distance(locations.get(next.placeId()),locations.get(p.placeId())))).orElseThrow();
                    builder.move(restaurant);
                    if(builder.time.isBefore(LocalTime.NOON) && !input.end().isBefore(LocalTime.of(13,0)))
                        builder.add("FREE",restaurant,"식사 전 자유시간",(int)Duration.between(builder.time,LocalTime.NOON).toMinutes(),"주변 휴식·자유시간",null,null);
                    builder.visit(restaurant,"MEAL",60,"식사 (가격 확인 필요)"); builder.meal=true;
                }
            }
            if(hotel!=null && day<dayCount) builder.visit(hotel,"STAY",30,"숙소 도착·휴식 (체크인 시간·예약 확인 필요)");
            days.add(new Day(day,input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(day-1),List.copyOf(builder.items)));
            previous=hotel!=null?hotel:null;
        }
        var result=new Result(places.destination().name(),List.copyOf(days),input.transport(),Instant.now());
        validate(input,result);
        return result;
    }

    public void checkInput(Input input) {
        var c=input.conditions(); var p=input.places();
        if(c.durationDays()==null || c.durationDays()<1 || c.durationDays()>7)
            throw failure("PERIOD_REQUIRED","여행 조건에서 1~7일의 기간을 먼저 확정해 주세요.");
        if(c.durationDays()>p.attractions().size() || p.attractions().isEmpty() || p.attractions().size()>8)
            throw failure("INSUFFICIENT_PLACES","현재 관광지 후보로 이 기간을 채울 수 없어요. 여행 기간을 줄이거나 다른 지역을 선택해 주세요.");
        if(input.start()==null || input.end()==null || input.start().getSecond()!=0 || input.end().getSecond()!=0
            || input.start().isBefore(LocalTime.of(8,0)) || input.end().isAfter(LocalTime.of(21,0))
            || Duration.between(input.start(),input.end()).toMinutes()<240 || input.transport()==null
            || input.visitMinutes()<30 || input.visitMinutes()>180)
            throw failure("INVALID_HOURS","하루 시간은 08:00~21:00 사이 4시간 이상, 관광지 체류는 30~180분으로 정해 주세요.");
        if(c.durationDays()>1 && c.accommodationNeeded()==null)
            throw failure("ACCOMMODATION_REQUIRED","여행 조건에서 숙박 필요 여부를 먼저 확정해 주세요.");
        if(p.restaurants().isEmpty() || (p.accommodationNeeded() && p.accommodations().isEmpty()))
            throw failure("INSUFFICIENT_PLACES","식사 또는 숙소 후보가 부족해요. 다른 지역을 선택해 주세요.");
        if(c.endDate()!=null && c.startDate()!=null && !c.endDate().equals(c.startDate().plusDays(c.durationDays()-1)))
            throw failure("INVALID_PERIOD","여행 날짜와 기간을 다시 확인해 주세요.");
    }

    // 저장 직전에도 기간·장소·중복·겹침·이동시간과 AI 보조 안내를 검사한다.
    public void validate(Input input,Result result) {
        checkInput(input);
        if(result.days().size()!=input.conditions().durationDays() || result.transport()!=input.transport()) throw invalid();
        checkAssist(result);
        Set<Long> expected=new HashSet<>(); input.places().attractions().forEach(p->expected.add(p.placeId()));
        // 수정안은 유지한 관광지와 새 관광지가 모두 배치 대상에 있어야 한다. 유지 장소를 빼거나 바꾼 결과는 받지 않는다.
        if(input.revision()!=null && (input.revision().kept().isEmpty()
                || !expected.containsAll(input.revision().kept().stream().map(PlaceRef::placeId).toList())
                || !expected.containsAll(input.revision().added().stream().map(PlaceRef::placeId).toList()))) throw invalid();
        Set<Long> seen=new HashSet<>(); Set<Long> allowed=new HashSet<>(expected);
        input.places().restaurants().forEach(p->allowed.add(p.placeId()));
        input.places().accommodations().forEach(p->allowed.add(p.placeId()));
        int dayNumber=0;
        for(var day:result.days()) {
            if(day.number()!=++dayNumber || day.items().isEmpty()) throw invalid();
            LocalDate date=input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(dayNumber-1);
            if(!Objects.equals(date,day.date())) throw invalid();
            LocalTime end=input.start();
            Long previous=dayNumber>1 && input.places().accommodationNeeded()?input.places().accommodations().getFirst().placeId():null;
            Long transferDestination=null; boolean transferred=false; int meals=0, visits=0;
            for(var item:day.items()) {
                if(item.start()==null || item.end()==null || !item.start().equals(end) || !item.end().isAfter(item.start())
                        || item.end().isAfter(input.end()) || !allowed.contains(item.placeId())) throw invalid();
                long minutes=Duration.between(item.start(),item.end()).toMinutes();
                if("TRANSIT".equals(item.type())) {
                    if(transferred || (previous!=null && previous.equals(item.placeId()))) throw invalid();
                    if(item.travelSeconds()!=null && (item.travelSeconds()<=0 || item.travelSeconds()>7200
                            || minutes!=(item.travelSeconds()+59)/60+10 || item.checkedAt()==null)) throw invalid();
                    if(item.travelSeconds()==null && minutes!=5) throw invalid();
                    transferred=true; transferDestination=item.placeId();
                } else {
                    if(previous!=null && !previous.equals(item.placeId()) && !transferred) throw invalid();
                    if(transferred && !item.placeId().equals(transferDestination)) throw invalid();
                    previous=item.placeId(); transferred=false;
                    switch(item.type()) {
                        case "VISIT" -> { if(!expected.contains(item.placeId()) || !seen.add(item.placeId()) || minutes!=input.visitMinutes()) throw invalid(); visits++; }
                        case "MEAL" -> { if(input.places().restaurants().stream().noneMatch(p->p.placeId().equals(item.placeId()))) throw invalid(); meals++; }
                        case "STAY" -> { if(input.places().accommodations().stream().noneMatch(p->p.placeId().equals(item.placeId()))) throw invalid(); }
                        case "FREE" -> { }
                        default -> throw invalid();
                    }
                    if(Set.of("VISIT","MEAL","STAY").contains(item.type())) {
                        var pool=new ArrayList<>(input.places().attractions()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
                        var place=pool.stream().filter(p->p.placeId().equals(item.placeId())).findFirst().orElseThrow(ItineraryGenerator::invalid);
                        OperatingHoursValidator.check(place.name(),place.operatingInfo(),date,item.start(),item.end());
                    }
                }
                end=item.end();
            }
            if(meals!=1 || visits==0 || transferred) throw invalid();
            if(input.places().accommodationNeeded() && dayNumber<result.days().size()
                && (!"STAY".equals(day.items().getLast().type())
                    || !input.places().accommodations().getFirst().placeId().equals(day.items().getLast().placeId()))) throw invalid();
        }
        if(!seen.equals(expected)) throw invalid();
    }

    private class DayBuilder {
        final Input input; final Map<Long,KakaoMapClient.Location> locations;
        final Map<String,KakaoMapClient.TravelTime> routes; final Instant deadline;
        final List<Item> items=new ArrayList<>(); LocalTime time; PlaceCandidate previous; boolean meal;
        final LocalDate date;
        DayBuilder(Input input,Map<Long,KakaoMapClient.Location> locations,Map<String,KakaoMapClient.TravelTime> routes,Instant deadline,PlaceCandidate previous,int day) {
            this.input=input; this.locations=locations; this.routes=routes; this.deadline=deadline; this.previous=previous; time=input.start();
            date=input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(day-1);
        }
        void visit(PlaceCandidate place,String type,int minutes,String note) {
            move(place); add(type,place,place.name(),minutes,note,null,null);
        }
        void move(PlaceCandidate next) {
            if(previous!=null && !previous.placeId().equals(next.placeId())) {
                checkDeadline(deadline);
                var a=locations.get(previous.placeId()); var b=locations.get(next.placeId());
                if(a.longitude()==b.longitude() && a.latitude()==b.latitude()) {
                    add("TRANSIT",next,previous.name()+" → "+next.name(),5,"같은 주소 내 이동 여유 5분 · 실제 내부 동선 확인 필요",null,null);
                } else {
                    var route=routes.computeIfAbsent(previous.placeId()+":"+next.placeId(),key->maps.travelTime(a,b,input.transport()));
                    if(route.minutes()>120) throw failure("TRAVEL_TIME_EXCEEDED","한 구간의 이동시간이 2시간을 넘어요. 다른 지역이나 이동수단을 선택해 주세요.");
                    add("TRANSIT",next,previous.name()+" → "+next.name(),route.minutes()+10,
                        "카카오 예상 "+route.minutes()+"분 + 이동 여유 10분",route.seconds(),route.checkedAt());
                }
            }
            previous=next;
        }
        void add(String type,PlaceCandidate place,String name,int minutes,String note,Integer seconds,Instant checkedAt) {
            if(minutes<=0 || time.toSecondOfDay()+minutes*60>input.end().toSecondOfDay())
                throw failure("TIME_OVERLAP","방문·식사·이동 시간이 하루 종료 시간을 넘어요. 이용 시간을 늘리거나 관광지 체류시간을 줄여 주세요.");
            var location=locations.get(place.placeId());
            if(Set.of("VISIT","MEAL","STAY").contains(type)) note += "\n" + OperatingHoursValidator.note(place.name(),place.operatingInfo(),date,time,time.plusMinutes(minutes));
            String map="https://map.kakao.com/link/map/"+URLEncoder.encode(place.name(),StandardCharsets.UTF_8).replace("+","%20")+","+location.latitude()+","+location.longitude();
            items.add(new Item(type,place.placeId(),name,time,time.plusMinutes(minutes),note,map,place.sourceUrl(),seconds,checkedAt));
            time=time.plusMinutes(minutes);
        }
    }
    // AI 보조 안내는 저장 직전에도 일차 수·번호와 문구 형식만 확인한다. 안내 내용은 검증된 사실이 아니다.
    private static void checkAssist(Result result) {
        var assist=result.assist();
        if(assist==null) return;
        if(assist.days()==null || assist.days().size()!=result.days().size() || assist.createdAt()==null) throw invalid();
        var numbers=new ArrayList<>(result.days().stream().map(Day::number).toList());
        for(var advice:assist.days()) {
            if(!numbers.remove((Integer)advice.number())) throw invalid();
            checkAssistText(advice.advice());
        }
        checkAssistText(assist.summary());
    }
    private static void checkAssistText(String text) {
        if(text==null || text.isBlank() || text.length()>600 || text.contains("<") || text.contains(">")) throw invalid();
    }
    // 안내된 정기휴무일이 겹치는 장소는 그날 선택에서 뒤로 미룬다. 휴무 정보가 없으면 기존 근거리 순서를 그대로 쓴다.
    private static Comparator<PlaceCandidate> order(LocalDate date,java.util.function.ToDoubleFunction<PlaceCandidate> nearest) {
        return Comparator.<PlaceCandidate>comparingInt(p->OperatingHoursValidator.closedOn(p.operatingInfo(),date)?1:0)
            .thenComparingDouble(nearest);
    }
    private static double distance(KakaoMapClient.Location a,KakaoMapClient.Location b) {
        double x=(a.longitude()-b.longitude())*Math.cos(Math.toRadians(a.latitude()));
        double y=a.latitude()-b.latitude(); return x*x+y*y;
    }
    private static void checkDeadline(Instant deadline) { if(Instant.now().isAfter(deadline)) throw failure("SCHEDULE_TIMEOUT","일정 처리 시간이 초과됐어요. 다시 요청해 주세요."); }
    private static RecommendationException invalid() { return failure("INVALID_SCHEDULE","일정의 시간과 장소를 검증하지 못했어요. 조건을 확인해 주세요."); }
    private static RecommendationException failure(String code,String message) { return new RecommendationException(code,message); }
}
