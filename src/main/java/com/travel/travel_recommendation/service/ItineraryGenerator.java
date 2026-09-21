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
        var used=new ArrayList<>(places.attractions()); used.addAll(places.restaurants());
        for(var place:used) {
            checkDeadline(deadline);
            locations.put(place.placeId(),locate(place,addresses));
        }
        // 숙소는 후보마다 위치를 확인해 관광지와 가까운 순서로 고른다(SFR-05-02). 위치를 못 찾은 후보만 뺀다.
        Lodging lodging=null; PlaceCandidate hotel=null;
        if(places.accommodationNeeded()) {
            RecommendationException lastError=null;
            for(var stay:places.accommodations()) {
                checkDeadline(deadline);
                try { locations.put(stay.placeId(),locate(stay,addresses)); }
                catch(RecommendationException error) { if("SCHEDULE_TIMEOUT".equals(error.getCode())) throw error; lastError=error; }
            }
            lodging=rankLodging(places,locations);
            if(lodging==null) throw lastError!=null?lastError:failure("INSUFFICIENT_PLACES","숙소 후보의 위치를 확인하지 못했어요. 다른 지역을 선택해 주세요.");
            Long chosen=lodging.chosenPlaceId();
            hotel=places.accommodations().stream().filter(p->p.placeId().equals(chosen)).findFirst().orElseThrow();
        }
        // 여러 날이면 같은 음식점이 반복되지 않도록 이미 고른 곳을 기억한다(SFR-17-01).
        Set<Long> usedRestaurants=new HashSet<>();
        var foods=RegionalFoods.of(places.destination().name());
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
                    // 식사는 방금 방문한 관광지에서 가까운 후보를 고른다. 아직 가지 않은 음식점이 있으면 그중에서 고른다.
                    var fresh=places.restaurants().stream().filter(p->!usedRestaurants.contains(p.placeId())).toList();
                    var restaurant=(fresh.isEmpty()?places.restaurants():fresh).stream().min(order(builder.date,
                        p->distance(locations.get(next.placeId()),locations.get(p.placeId())))).orElseThrow();
                    usedRestaurants.add(restaurant.placeId());
                    builder.move(restaurant);
                    if(builder.time.isBefore(LocalTime.NOON) && !input.end().isBefore(LocalTime.of(13,0)))
                        builder.add("FREE",restaurant,"식사 전 자유시간",(int)Duration.between(builder.time,LocalTime.NOON).toMinutes(),"주변 휴식·자유시간",null,null);
                    // 음식 분류와 고른 이유(직전 관광지와의 거리), 지역 대표 음식을 함께 적는다(SFR-17-01·02).
                    String mealNote="식사 (가격 확인 필요)\n"
                        +(restaurant.subtype()==null || restaurant.subtype().isBlank()?"":"음식 분류: "+restaurant.subtype()+" · ")
                        +next.name()+"에서 직선 약 "+kilometersText(locations.get(next.placeId()),locations.get(restaurant.placeId()))+"라 골랐어요."
                        +foods.map(g->"\n이 지역 대표 "+(g.snack()?"간식":"음식")+": "+g.joined()+" (이 음식점에서 파는지는 확인 필요)").orElse("");
                    builder.visit(restaurant,"MEAL",60,mealNote); builder.meal=true;
                }
            }
            if(hotel!=null && day<dayCount) builder.visit(hotel,"STAY",30,"숙소 도착·휴식 (체크인 시간·예약 확인 필요)\n추천 이유: "+lodging.reason());
            days.add(new Day(day,input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(day-1),List.copyOf(builder.items)));
            previous=hotel!=null?hotel:null;
        }
        var result=new Result(places.destination().name(),List.copyOf(days),input.transport(),Instant.now()).withLodging(lodging);
        validate(input,result);
        return result;
    }

    // 카카오 후보는 조회해 둔 좌표를, 그 밖에는 도로명 주소로 좌표를 찾는다. 같은 건물 주소는 한 번만 조회한다.
    private KakaoMapClient.Location locate(PlaceCandidate place,Map<String,KakaoMapClient.Location> addresses) {
        if("KAKAO".equals(place.sourceProvider()) && place.longitude()!=null && place.latitude()!=null) {
            if(place.verifiedAt()==null || place.verifiedAt().isBefore(LocalDateTime.now(ZoneOffset.UTC).minusHours(24)))
                throw failure("PLACE_STALE","장소 조회 결과가 오래됐어요. 장소 목록에서 다시 조회해 주세요.");
            return new KakaoMapClient.Location(place.address(),place.longitude(),place.latitude(),place.verifiedAt().toInstant(ZoneOffset.UTC));
        }
        var matcher=ROAD.matcher(place.address());
        if(!matcher.matches()) throw failure("ADDRESS_REQUIRED","장소의 도로명 주소를 확인해야 해요.");
        return addresses.computeIfAbsent(matcher.group(1),maps::locate);
    }

    /**
     * 숙소 후보를 이번 여행 관광지들과의 평균 직선거리가 짧은 순서로 정렬한다(SFR-05-02·03).
     * 거리가 같으면 후보 목록 순서를 따른다. 위치를 확인한 후보가 없으면 null이다.
     */
    static Lodging rankLodging(PlaceCandidateView places,Map<Long,KakaoMapClient.Location> locations) {
        List<StayOption> options=new ArrayList<>();
        for(var stay:places.accommodations()) {
            var at=locations.get(stay.placeId());
            if(at==null) continue;
            double total=0;
            for(var attraction:places.attractions()) total+=kilometers(at,locations.get(attraction.placeId()));
            double average=Math.round(total/places.attractions().size()*10)/10.0;
            options.add(new StayOption(stay.placeId(),stay.name(),stay.subtype(),stay.address(),average,stay.sourceUrl(),stay.mapSearchUrl()));
        }
        if(options.isEmpty()) return null;
        options.sort(Comparator.comparingDouble(StayOption::averageKilometers));
        var best=options.getFirst();
        String reason=options.size()==1
            ?"확인된 숙소 후보가 1곳이라 골랐어요. 방문할 관광지 "+places.attractions().size()+"곳과의 평균 직선거리는 약 "+best.averageKilometers()+"km예요."
            :"방문할 관광지 "+places.attractions().size()+"곳과의 평균 직선거리가 약 "+best.averageKilometers()+"km로 숙소 후보 "+options.size()+"곳 중 가장 가까워요.";
        return new Lodging(best.placeId(),reason,options.subList(0,Math.min(3,options.size())));
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
        // 필수 방문 장소는 배치 대상에 모두 있어야 한다(SFR-02-07).
        var constraints=input.constraints();
        if(constraints!=null && !expected.containsAll(constraints.requiredPlaceIds())) throw invalid();
        Set<Long> seen=new HashSet<>(); Set<Long> allowed=new HashSet<>(expected);
        input.places().restaurants().forEach(p->allowed.add(p.placeId()));
        input.places().accommodations().forEach(p->allowed.add(p.placeId()));
        // 숙소는 첫 후보로 고정하지 않고 동선에 맞춰 고르므로, 첫날 마지막 숙소를 기준으로 모든 날이 같은 숙소인지 본다.
        Long hotelId=null;
        if(input.places().accommodationNeeded() && result.days().size()>1) {
            var firstDay=result.days().getFirst().items();
            if(firstDay.isEmpty() || !"STAY".equals(firstDay.getLast().type())) throw invalid();
            hotelId=firstDay.getLast().placeId();
            if(result.lodging()!=null && !hotelId.equals(result.lodging().chosenPlaceId())) throw invalid();
        }
        int dayNumber=0;
        for(var day:result.days()) {
            if(day.number()!=++dayNumber || day.items().isEmpty()) throw invalid();
            LocalDate date=input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(dayNumber-1);
            if(!Objects.equals(date,day.date())) throw invalid();
            LocalTime end=input.start();
            Long previous=dayNumber>1?hotelId:null;
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
            // 하루 방문 수가 필수 조건이면 매일 그 수를 지켜야 한다.
            if(constraints!=null && constraints.dailyVisitsRequired() && visits!=constraints.dailyVisits()) throw invalid();
            if(input.places().accommodationNeeded() && dayNumber<result.days().size()
                && (!"STAY".equals(day.items().getLast().type())
                    || !Objects.equals(hotelId,day.items().getLast().placeId()))) throw invalid();
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
                    // 대중교통이면 탈 노선·환승·요금 요약을 함께 적는다(SFR-06-04). 요약을 못 읽으면 시간만 적는다.
                    add("TRANSIT",next,previous.name()+" → "+next.name(),route.minutes()+10,
                        "카카오 예상 "+route.minutes()+"분 + 이동 여유 10분"
                            +(route.summary()==null?"":"\n"+route.summary()+" (조회 시점 기준, 배차·요금은 이용 전 확인)"),
                        route.seconds(),route.checkedAt());
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
    /** 화면에 보여 줄 직선거리(km). 위도 1도를 약 111km로 보는 근사값이다. */
    static double kilometers(KakaoMapClient.Location a,KakaoMapClient.Location b) {
        return Math.sqrt(distance(a,b))*111.0;
    }
    private static String kilometersText(KakaoMapClient.Location a,KakaoMapClient.Location b) {
        double km=kilometers(a,b);
        return km<1?Math.max(100,Math.round(km*10)*100)+"m":(Math.round(km*10)/10.0)+"km";
    }
    private static void checkDeadline(Instant deadline) { if(Instant.now().isAfter(deadline)) throw failure("SCHEDULE_TIMEOUT","일정 처리 시간이 초과됐어요. 다시 요청해 주세요."); }
    private static RecommendationException invalid() { return failure("INVALID_SCHEDULE","일정의 시간과 장소를 검증하지 못했어요. 조건을 확인해 주세요."); }
    private static RecommendationException failure(String code,String message) { return new RecommendationException(code,message); }
}
