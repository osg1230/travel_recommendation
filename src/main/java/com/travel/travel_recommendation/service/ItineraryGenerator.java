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
    /** 도보로 추정한 이동 항목 메모의 시작 문구. 조건 확인 결과에서 추정 구간을 구분하는 데 쓴다. */
    static final String WALK_NOTE_PREFIX="도보 추정 ";
    private static final Pattern ROAD=Pattern.compile("^(.+?(?:로|길)\\s+\\d+(?:-\\d+)?)(?:\\s+.*)?$");
    /** 같은 종류 관광지를 연달아 고르지 않도록 거리(제곱값)에 곱하는 수. 4는 실제 거리 두 배에 해당한다. */
    private static final double SAME_KIND_PENALTY=4;
    /** 저녁 식사는 이 시각 이후에만 넣는다. */
    private static final LocalTime DINNER_FROM=LocalTime.of(17,0);
    /** 이 시각이 지났는데 다음 관광지를 마치면 LUNCH_UNTIL을 넘기면 관광지보다 점심을 먼저 넣는다. */
    private static final LocalTime LUNCH_FROM=LocalTime.of(11,0), LUNCH_UNTIL=LocalTime.of(13,30);
    /** 남는 시간을 예비 관광지로 채울 때도 수정안의 교체 후보로 남겨 둘 예비 관광지 수. */
    private static final int SPARE_KEPT=2;
    /**
     * 남는 시간을 채우는 예비 관광지는 직전 방문지에서 이 직선거리(km) 안에 있는 곳만 넣는다(자동차로 대략 20분).
     * 시간이 남았다는 이유만으로 먼 곳까지 다녀오게 하지 않고, 가까운 곳이 없으면 그 시간은 비워 둔다.
     */
    private static final double SPARE_MAX_KM=10.0;
    /** 하루에 시간이 모자라 넣지 못한 관광지가 이만큼 나오면 그날은 마친다(이동시간 조회를 무한히 늘리지 않게). */
    private static final int MAX_MISSES=3;
    /**
     * 중심 장소끼리 이 직선거리(km) 안이면 같은 날 함께 들를 곳으로 보고 날을 나누지 않는다.
     * 예) 불국사·석굴암 약 1.8km, 경기전·전주한옥마을 약 0.4km는 묶고, 2km보다 떨어진 곳은 날마다 따로 둔다.
     */
    private static final double SAME_DAY_CENTER_KM=2.0;
    /** 관광지를 더 넣어 볼 때 이 오류가 나면 실패가 아니라 '오늘은 더 들어가지 않음'으로 본다. */
    private static final Set<String> DAY_FULL_CODES=Set.of("TIME_OVERLAP","OPERATING_CONFLICT");
    /** 저녁 관광 한 곳의 체류시간(분). 야경·산책·야시장을 둘러보는 시간이다. */
    static final int EVENING_MINUTES=60;
    /** 저녁 관광지까지 이 시간(분, 여유 10분 포함)보다 오래 걸리면 고르지 않는다. 밤에 멀리 오가지 않게 한다(자동차 약 30분 거리). */
    private static final int EVENING_MAX_MOVE=45;
    /** 저녁 관광을 마치고 숙소로 돌아와 쉬는 항목의 길이(분). */
    static final int RETURN_REST_MINUTES=30;
    /** 이 오류가 나면 그 관광지만 뺀다. 한 구간 2시간 초과(섬 등)와 경로 없음이다. */
    private static final Set<String> UNREACHABLE_CODES=Set.of("TRAVEL_TIME_EXCEEDED","MAP_NO_ROUTE");

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
            lodging=rankLodging(places,locations,input.constraints()==null?null:input.constraints().stayTier());
            // 수정 요청으로 숙소를 정했으면(확인된 숙소 후보 중) 그곳을 쓴다. 다른 후보 안내는 거리 순서 그대로 둔다.
            Long chosenStay=input.choices().stayPlaceId();
            if(lodging!=null && chosenStay!=null && locations.containsKey(chosenStay)) {
                var options=new ArrayList<StayOption>(lodging.options());
                options.removeIf(o->o.placeId().equals(chosenStay));
                var all=rankLodging(places,locations,null);
                all.options().stream().filter(o->o.placeId().equals(chosenStay)).findFirst().ifPresent(o->options.addFirst(o));
                lodging=new Lodging(chosenStay,"고른 숙소를 그대로 써요.",options.subList(0,Math.min(3,options.size())));
            }
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
        var constraints=input.constraints();
        var mustVisit=mustVisit(input);
        // 섬처럼 차·대중교통으로 2시간 안에 갈 수 없거나 경로가 없는 관광지. 그곳만 빼고 일정을 만든다.
        List<PlaceCandidate> unreachable=new ArrayList<>();
        RecommendationException routeError=null;
        // 뺀 관광지 자리를 채울 예비 관광지. 쓸 때 위치를 확인한다.
        var reserve=new ArrayList<>(input.reserve());
        // 직전에 간 관광지 종류(예: 해변). 날이 바뀌어도 이어서 본다.
        String lastKind="";
        // 일정의 중심 장소: 1일차부터 하루에 한 곳씩 그날 첫 관광지로 넣는다. 나머지 관광지는 가장 가까운 중심 장소의 날에 먼저 넣는다.
        // 서로 가까운 중심 장소(예: 불국사·석굴암)는 날을 나누면 다음 날 같은 곳으로 되돌아가게 되므로 앞의 곳 하루에 묶는다.
        var chosenCenters=centerPlaces(input,dayCount);
        var centers=separateCenters(chosenCenters,locations);
        // 하루 방문 수를 정했으면 정한 수만 넣고, 남는 시간을 예비 관광지로 채우지 않는다.
        boolean fillSpareTime=constraints==null || constraints.dailyVisits()==null;
        Map<Long,Integer> dayOf=new HashMap<>();
        for(var place:places.attractions()) {
            int best=-1; double bestDistance=Double.MAX_VALUE;
            for(int i=0;i<centers.size();i++) {
                double d=distance(locations.get(centers.get(i).placeId()),locations.get(place.placeId()));
                if(d<bestDistance) { bestDistance=d; best=i; }
            }
            if(best>=0) dayOf.put(place.placeId(),best+1);
        }
        for(int day=1;day<=dayCount;day++) {
            var builder=new DayBuilder(input,locations,routes,deadline,previous,day,usedRestaurants,foods);
            PlaceCandidate stay=hotel!=null && day<dayCount?hotel:null;
            boolean dinnerDay=TripConstraints.includesDinner(builder.end);
            // 첫날 집에서 출발하면 집 → 여행지 이동을 맨 앞에 넣는다. 관광은 도착한 뒤부터다.
            if(day==1 && input.home()!=null) builder.homeDeparture();
            // 남은 관광지를 남은 날짜에 고르게 나눈 수가 오늘의 최대치다. 시간이 모자라면 그보다 적게 넣고 다음 날로 넘긴다.
            int target=(remaining.size()+dayCount-day)/(dayCount-day+1);
            // 하루 방문 수를 정했으면 낮 관광도 그 수를 넘기지 않는다(저녁 관광으로 채운 날의 남은 관광지가 다음 날로 몰리지 않게).
            if(!fillSpareTime) target=Math.min(target,constraints.dailyVisits());
            PlaceCandidate last=null; int visits=0;
            final int today=day;
            PlaceCandidate dayCenter=day<=centers.size()?centers.get(day-1):null;
            boolean centerTried=false; Set<Long> skipToday=new HashSet<>(); int misses=0;
            while(misses<MAX_MISSES) {
                // 오늘 몫을 다 넣었거나 남은 관광지가 오늘 시간에 들어가지 않으면, 하루 시간이 남는 한 예비 관광지 중 가까운 곳을 더 넣는다.
                boolean spare=visits>=target || remaining.stream().allMatch(p->skipToday.contains(p.placeId()));
                if(spare && (!fillSpareTime || visits==0)) break;
                // 예비 관광지로 더 채워도 하루 관광지는 TripConstraints.MAX_AUTO_DAILY곳까지다. 남는 시간은 쉬거나 저녁 관광에 쓴다.
                if(spare && visits>=TripConstraints.MAX_AUTO_DAILY) break;
                PlaceCandidate anchor=builder.previous; String previousKind=lastKind;
                List<PlaceCandidate> pool;
                if(spare) {
                    // 예비 관광지는 수정안에서 바꿔 넣을 후보로도 쓰이므로 SPARE_KEPT곳은 남겨 둔다.
                    var located=locatedReserve(reserve,locations,addresses,deadline);
                    pool=located.stream().filter(p->!skipToday.contains(p.placeId())
                        && (anchor==null || kilometers(locations.get(anchor.placeId()),locations.get(p.placeId()))<=SPARE_MAX_KM)).toList();
                    if(located.size()<=SPARE_KEPT || pool.isEmpty()) break;
                } else {
                    // 오늘 고를 수 있는 곳: 다른 날의 중심 장소는 빼고, 가장 가까운 중심 장소가 오늘인 곳을 먼저 본다.
                    var choices=remaining.stream().filter(p->!skipToday.contains(p.placeId()) && !(centers.contains(p) && !p.equals(dayCenter))).toList();
                    if(choices.isEmpty()) choices=remaining.stream().filter(p->!skipToday.contains(p.placeId())).toList();
                    if(choices.isEmpty()) break;
                    var own=choices.stream().filter(p->dayOf.getOrDefault(p.placeId(),today)==today).toList();
                    pool=own.isEmpty()?choices:own;
                }
                // 하루의 첫 관광지는 그날의 중심 장소다. 그 뒤로는 가까운 관광지를 이어서 고른다. 최단 경로 보장은 아니다.
                // 직전 관광지와 종류가 같으면(예: 해수욕장 다음 해수욕장) 거리를 두 배로 쳐서, 조금 멀어도 다른 종류를 먼저 고른다.
                boolean forceCenter=!spare && !centerTried && dayCenter!=null && remaining.contains(dayCenter);
                centerTried=true;
                // 빠지면 안 되는 곳(필수·수정 요청으로 더한 곳)을 그날 먼저 넣고, 그 안에서는 가까운 곳부터 고른다.
                var next=forceCenter?dayCenter:pool.stream().min(Comparator.<PlaceCandidate>comparingInt(p->mustVisit.contains(p.placeId())?0:1)
                    .thenComparing(order(builder.date,p->anchor==null?remaining.indexOf(p)
                    :distance(locations.get(anchor.placeId()),locations.get(p.placeId()))
                        *(!previousKind.isEmpty() && previousKind.equals(TripConstraints.kind(p))?SAME_KIND_PENALTY:1)))).orElseThrow();
                // 거리만 봤다면 직전과 같은 종류를 골랐을 때만 "다른 종류로 골랐다"고 이유에 적는다.
                boolean variedKind=!forceCenter && anchor!=null && !previousKind.isEmpty() && pool.stream()
                    .min(order(builder.date,p->distance(locations.get(anchor.placeId()),locations.get(p.placeId()))))
                    .filter(p->!p.equals(next) && previousKind.equals(TripConstraints.kind(p))).isPresent();
                var mark=builder.mark();
                boolean hadLunch=builder.lunch;
                try {
                    // 점심때가 됐는데 이 관광지를 마치면 너무 늦어지면(예: 12:06~13:36) 점심을 먼저 먹는다.
                    // 하루의 첫 관광지 전(예: 집에서 출발해 12:40 도착)이면 그 관광지 근처에서 먹는다.
                    if(!builder.lunch && lunchFirst(builder,next)) eat(builder,builder.previous==null?next:builder.previous,false);
                    builder.visit(next,"VISIT",input.visitMinutes(),visitNote(input,next,spare,centers.contains(next),
                        chosenCenters.contains(next),variedKind?previousKind:null));
                    if(!builder.lunch && !builder.time.isBefore(LocalTime.NOON)) eat(builder,next,false);
                    // 이 관광지를 넣어도 남은 점심·숙소 도착까지 하루 종료 시간 안에 끝나는지 미리 확인한다.
                    checkClosing(builder,next,stay);
                } catch(RecommendationException error) {
                    // 더 넣어 보던 예비 관광지가 멀면 그곳만 빼고 다른 예비 관광지를 본다.
                    if(spare && UNREACHABLE_CODES.contains(error.getCode())) { builder.reset(mark); reserve.remove(next); continue; }
                    // 이 관광지를 넣으려다 한 구간이 2시간을 넘거나 경로가 없으면 이 곳만 빼고 다음 후보를 본다.
                    if(UNREACHABLE_CODES.contains(error.getCode())) {
                        if(mustVisit.contains(next.placeId()))
                            throw failure(error.getCode(),"'"+next.name()+"'까지 가는 길이 2시간을 넘거나 경로를 찾지 못했어요. 필수 방문 장소에서 빼거나 이동수단을 바꿔 주세요.");
                        builder.reset(mark); remaining.remove(next); unreachable.add(next); routeError=error;
                        // 빠진 자리는 예비 관광지로 채운다. 이미 일정에 있는 종류와 다른 곳을 먼저 쓴다.
                        var replacement=takeReserve(reserve,remaining,days,builder,locations,addresses,deadline);
                        if(replacement!=null) remaining.add(replacement);
                        continue;
                    }
                    // 중심 장소가 오늘 휴무 등으로 들어가지 않으면 오늘은 다른 곳부터 시작한다.
                    if(forceCenter && DAY_FULL_CODES.contains(error.getCode())) { builder.reset(mark); skipToday.add(next.placeId()); continue; }
                    // 첫 관광지부터 들어가지 않으면 그대로 실패를 알린다. 그 뒤라면 이 곳은 다음 날로 넘기고 오늘 들어갈 다른 곳을 본다.
                    if(visits==0 || !DAY_FULL_CODES.contains(error.getCode())) throw error;
                    builder.reset(mark); skipToday.add(next.placeId()); misses++; continue;
                }
                if(spare) reserve.remove(next); else remaining.remove(next);
                last=next; visits++; lastKind=TripConstraints.kind(next);
                // 점심 뒤에 관광을 이어 가 17시가 지났으면 저녁 식사도 넣어 본다. 시간이 안 되면 저녁 없이 마친다.
                if(dinnerDay && hadLunch && !builder.dinner && !builder.time.isBefore(DINNER_FROM)) {
                    var beforeDinner=builder.mark();
                    try { eat(builder,next,true); checkClosing(builder,next,stay); }
                    catch(RecommendationException error) {
                        if(!DAY_FULL_CODES.contains(error.getCode())) throw error;
                        builder.reset(beforeDinner);
                    }
                }
            }
            // 갈 수 있는 관광지가 남지 않아 하루를 비워 두게 되면 일정을 만들지 않는다.
            if(visits==0) throw routeError!=null?routeError:failure("INSUFFICIENT_PLACES",day+"일차에 넣을 관광지가 부족해요. 여행 기간을 줄이거나 장소 목록에서 후보를 더 찾아 주세요.");
            // 점심을 아직 넣지 못했으면 마지막 관광지 뒤에 넣는다. 들어가는지는 위에서 확인했다.
            if(!builder.lunch) eat(builder,last,false);
            // 숙소를 고른 이유는 따로 적지 않고, 다른 후보와 체크인 확인 안내는 초안의 첫 숙박 항목에 한 번만 보여 준다.
            // 하루 방문 수를 정했으면 저녁 관광도 그 수에 센다(예: 하루 5곳 = 낮 4곳 + 저녁 1곳).
            // 낮 관광으로 이미 정한 수를 채웠으면 저녁 관광은 넣지 않고 숙소에서 쉰다.
            int eveningVisits=0;
            if(stay!=null) {
                arriveAndDine(builder,stay,"숙소 도착·휴식");
                if(fillSpareTime || visits<constraints.dailyVisits()) eveningVisits=addEvening(builder,stay,days,addresses)?1:0;
            }
            // 마지막 날 귀가 시각을 정했으면 마지막 장소 → 집 이동을 맨 뒤에 넣는다.
            if(day==dayCount && input.home()!=null) builder.homeReturn();
            // 하루 방문 수가 필수 조건이면 시간 때문에 줄어든 날을 그대로 두지 않고 이유를 알린다.
            if(constraints!=null && constraints.dailyVisitsRequired() && visits+eveningVisits!=constraints.dailyVisits())
                throw failure("TIME_OVERLAP",day+"일차에 관광지 "+constraints.dailyVisits()+"곳을 하루 시간 안에 넣지 못했어요. 하루 시간을 늘리거나 체류시간·하루 방문 수를 줄여 주세요.");
            days.add(new Day(day,builder.date,List.copyOf(builder.items)));
            previous=hotel!=null?hotel:null;
        }
        // 끝까지 넣지 못한 관광지는 일정에서 빼고 결과에 적는다. 필수·유지 장소가 남았으면 일정을 만들지 않는다.
        var missed=remaining.stream().filter(p->mustVisit.contains(p.placeId())).map(PlaceCandidate::name).toList();
        if(!missed.isEmpty())
            throw failure("TIME_OVERLAP","하루 시간 안에 '"+String.join("', '",missed)+"'을(를) 넣지 못했어요. 하루 시간을 늘리거나 관광지 체류시간을 줄여 주세요.");
        var skipped=new ArrayList<>(unreachable); skipped.addAll(remaining);
        var result=new Result(places.destination().name(),List.copyOf(days),input.transport(),Instant.now()).withLodging(lodging)
            .withSkipped(skipped.stream().map(p->new PlaceRef(p.placeId(),p.name())).toList());
        validate(input,result);
        return result;
    }

    /**
     * 관광지 항목의 설명. 분류와 함께, 실제로 이 곳을 고르는 데 쓴 기준(필수 방문·여행 목적·취향·중심 장소·종류 섞기)만 이유로 적는다.
     * 거리는 모든 관광지에 공통인 기준이라 이유로 따로 적지 않는다. 해당하는 기준이 없으면 분류만 보여 준다
     * (취향을 골랐다면 취향 후보가 모자라 채운 곳이라고 알린다).
     */
    private static String visitNote(Input input,PlaceCandidate place,boolean spare,boolean dayCenter,boolean chosenCenter,String avoidedKind) {
        var reasons=new ArrayList<String>();
        if(input.constraints()!=null && input.constraints().requiredPlaceIds().contains(place.placeId())) reasons.add("필수 방문 장소로 고른 곳이에요.");
        if(input.revision()!=null && input.revision().added().stream().anyMatch(p->p.placeId().equals(place.placeId())))
            reasons.add("수정 요청에 따라 새로 넣은 곳이에요.");
        if(input.constraints()!=null && input.constraints().festival()!=null && place.placeId().equals(input.constraints().festival().placeId()))
            reasons.add("여행 기간에 이곳이나 바로 근처에서 "+input.constraints().festival().title()+"("+input.constraints().festival().period()
                +") 행사가 열려요. 정확한 행사 장소와 일정은 출발 전에 확인해 주세요.");
        var conditions=input.conditions();
        if(conditions.purposeSearch()!=null && conditions.travelPurpose()!=null && !conditions.travelPurpose().isBlank()
                && conditions.purposeSearch().places().stream().anyMatch(p->place.placeId().equals(p.placeId())))
            reasons.add(conditions.purposeLabel()+"('"+conditions.travelPurpose().strip()+"')과 관련된 시설로 찾은 곳이에요.");
        var themes=ThemePlaces.matched(place.name(),place.subtype(),conditions.themeNames());
        if(!themes.isEmpty()) reasons.add("고른 여행 취향("+String.join("·",themes)+")에 맞는 분류예요.");
        if(dayCenter) reasons.add("이 날 일정의 중심으로 고른 곳이라 첫 방문지로 넣었어요.");
        else if(chosenCenter) reasons.add("일정의 중심으로 고른 곳이에요. 가까운 중심 장소와 같은 날로 묶었어요.");
        if(avoidedKind!=null) reasons.add("바로 앞 방문지와 같은 종류("+avoidedKind.replace(",","·")+")가 이어지지 않게 골랐어요.");
        if(spare) reasons.add("하루 시간이 남아 더 넣은 곳이에요.");
        // 취향을 골랐는데 어느 기준에도 해당하지 않으면, 취향에 맞는 후보가 모자라 다른 종류로 채운 곳이다(TripConstraints.aroundCenters 순서).
        // AI가 순서를 정한 일정에서는 분류 글자와 상관없이 갈 만한 곳으로 고른 것이라 이 안내를 붙이지 않는다.
        else if(reasons.isEmpty() && avoidedKind==null && !input.pickedByAi() && !ThemePlaces.placeThemes(conditions.themeNames()).isEmpty())
            reasons.add("고른 취향에 맞는 관광지 후보가 모자라 이 지역의 다른 종류 관광지로 채운 곳이에요.");
        return "관광·관람"+(place.subtypeLabel().isEmpty()?"":" · "+place.subtypeLabel())
            +(reasons.isEmpty()?"":"\n고른 이유: "+String.join(" ",reasons))
            +(place.religiousSite()?"\n종교 시설이라 미사·예불 등 행사 중에는 관람이 제한될 수 있어요. 방문 전 관람 가능 시간을 확인해 주세요.":"");
    }

    /**
     * 방금 방문한 관광지(after)에서 가까운 음식점으로 식사를 넣는다. 아직 가지 않은 음식점이 있으면 그중에서 고른다.
     * 점심은 12시 전에 도착하면 12시까지 자유시간을 두고, 저녁은 17시 이후에만 넣는다.
     */
    private void eat(DayBuilder builder,PlaceCandidate after,boolean dinner) {
        eat(builder,after,dinner,pickRestaurant(builder,after));
    }

    /**
     * after 주변의 음식점. 가장 가까운 곳에서 MEAL_CHOICE_KM 안쪽은 이동 부담이 비슷하다고 보고, 그중
     * ① 이번 여행에서 아직 먹지 않은 음식 분류 ② 인기 음식점(관광공사 연관 관광지에서 여행자들이 함께 많이 간 곳)
     * ③ 지역 대표 음식이 상호·분류에 들어간 곳 ④ 가까운 곳 순서로 고른다.
     * 이미 간 곳은 후보가 모자랄 때만 다시 고른다. 간식·술집·카페는 끼니로 어울리지 않아, 다른 음식점이 하나도 없을 때만 고른다.
     */
    private PlaceCandidate pickRestaurant(DayBuilder builder,PlaceCandidate after) {
        var choices=builder.input.choices();
        // 수정 요청에서 빼 달라고 한 식당은 쓰지 않는다. 모두 빼면 식사를 넣을 수 없으므로 그때만 다시 쓴다.
        var allowed=builder.input.places().restaurants().stream().filter(p->!choices.avoidRestaurants().contains(p.placeId())).toList();
        var all=allowed.isEmpty()?builder.input.places().restaurants():allowed;
        var meals=all.stream().filter(p->!PlaceCandidate.notForMeals(p.subtype())).toList();
        var restaurants=meals.isEmpty()?all:meals;
        var fresh=restaurants.stream().filter(p->!builder.usedRestaurants.contains(p.placeId())).toList();
        var from=builder.locations.get(after.placeId());
        // 수정 요청에서 가고 싶다고 한 식당은 아직 가지 않았고 PREFERRED_MEAL_KM 안이면 먼저 쓴다.
        var preferred=all.stream().filter(p->choices.preferRestaurants().contains(p.placeId()) && !builder.usedRestaurants.contains(p.placeId())
                && kilometers(from,builder.locations.get(p.placeId()))<=PREFERRED_MEAL_KM)
            .min(Comparator.comparingDouble(p->distance(from,builder.locations.get(p.placeId()))));
        if(preferred.isPresent()) return preferred.get();
        var sorted=(fresh.isEmpty()?restaurants:fresh).stream()
            .sorted(order(builder.date,p->distance(from,builder.locations.get(p.placeId())))).toList();
        var nearest=sorted.getFirst();
        boolean nearestClosed=OperatingHoursValidator.closedOn(nearest.operatingInfo(),builder.date);
        double limit=kilometers(from,builder.locations.get(nearest.placeId()))+MEAL_CHOICE_KM;
        var eaten=eatenKinds(builder);
        // sorted가 가까운 순서라 min은 우선순위가 같으면 더 가까운 곳을 고른다.
        return sorted.stream()
            .filter(p->OperatingHoursValidator.closedOn(p.operatingInfo(),builder.date)==nearestClosed
                && kilometers(from,builder.locations.get(p.placeId()))<=limit)
            .min(Comparator.<PlaceCandidate>comparingInt(p->eaten.contains(p.subtypeLabel())?1:0)
                .thenComparingInt(p->p.popular()?0:1)
                .thenComparingInt(p->regionalFood(builder,p).isPresent()?0:1)).orElse(nearest);
    }

    /** 수정 요청에서 가고 싶다고 한 식당은 식사 직전 장소에서 이 거리(km) 안이면 먼저 고른다. 더 멀면 동선이 크게 꼬이므로 쓰지 않는다. */
    static final double PREFERRED_MEAL_KM=10.0;
    /** 가장 가까운 음식점보다 이 거리(km)까지 더 멀어도, 아직 먹지 않은 음식이나 지역 대표 음식이면 먼저 고른다. */
    static final double MEAL_CHOICE_KM=2.0;
    /** 이보다 먼 음식점은 가까운 후보가 없어 넣었다고 안내한다. */
    static final double FAR_MEAL_KM=10.0;

    /** 이번 여행에서 이미 먹은 음식 분류(예: 한식·국수). */
    private static Set<String> eatenKinds(DayBuilder builder) {
        var kinds=new LinkedHashSet<String>();
        builder.input.places().restaurants().stream().filter(p->builder.usedRestaurants.contains(p.placeId()))
            .map(PlaceCandidate::subtypeLabel).filter(k->!k.isEmpty()).forEach(kinds::add);
        return kinds;
    }

    /** 상호나 분류에 들어 있는 지역 대표 음식(예: 부산 "밀면"). 메뉴를 확인한 것은 아니다. */
    private static Optional<String> regionalFood(DayBuilder builder,PlaceCandidate restaurant) {
        String text=restaurant.name()+" "+(restaurant.subtype()==null?"":restaurant.subtype());
        return builder.foods.flatMap(g->g.foods().stream().filter(text::contains).findFirst());
    }

    private void eat(DayBuilder builder,PlaceCandidate after,boolean dinner,PlaceCandidate restaurant) {
        boolean fresh=!builder.usedRestaurants.contains(restaurant.placeId());
        var eaten=eatenKinds(builder);
        builder.usedRestaurants.add(restaurant.placeId());
        builder.move(restaurant);
        if(!dinner && builder.time.isBefore(LocalTime.NOON) && !builder.end.isBefore(LocalTime.of(13,0)))
            builder.add("FREE",restaurant,"식사 전 자유시간",(int)Duration.between(builder.time,LocalTime.NOON).toMinutes(),"주변 휴식·자유시간",null,null);
        // 음식 분류와 고른 이유를 적는다(SFR-17-01). 이유는 pickRestaurant가 실제로 쓴 기준(아직 먹지 않은 음식·인기 음식점·지역 대표 음식)만 적고,
        // 거리는 이유가 아니라 위치 정보로 함께 보여 준다. 지역 대표 음식 안내(SFR-17-02)는 끼니마다 반복하지 않고 여행의 첫 식사에만 적는다.
        boolean firstMeal=builder.usedRestaurants.size()==1 && fresh;
        String kind=restaurant.subtypeLabel();
        var reasons=new ArrayList<String>();
        if(builder.input.choices().preferRestaurants().contains(restaurant.placeId())) reasons.add("수정 요청에서 가고 싶다고 한 식당이에요.");
        if(restaurant.popular()) reasons.add("한국관광공사 연관 관광지 정보에서 이 지역 여행자들이 함께 많이 찾은 음식점이에요.");
        regionalFood(builder,restaurant).ifPresent(food->reasons.add("상호나 분류에 이 지역 대표 음식("+food+")이 들어간 곳이에요."));
        if(fresh && !kind.isEmpty() && !eaten.isEmpty() && !eaten.contains(kind))
            reasons.add("앞서 먹은 음식("+String.join("·",eaten)+")과 다른 분류예요.");
        var from=builder.locations.get(after.placeId()); var to=builder.locations.get(restaurant.placeId());
        String where=kilometers(from,to)>FAR_MEAL_KM
            ?after.name()+"에서 직선 약 "+kilometersText(from,to)+" 떨어져 있어요. 더 가까운 곳에 확인된 음식점 후보가 없었어요."
            :after.name()+"에서 직선 약 "+kilometersText(from,to)+"예요.";
        String mealNote=(dinner?"저녁 식사":"식사")
            +(kind.isEmpty()?"":" · "+kind)
            +(reasons.isEmpty()?"":"\n고른 이유: "+String.join(" ",reasons))
            +"\n위치: "+where
            +(firstMeal?builder.foods.map(g->"\n이 지역 대표 "+(g.snack()?"간식":"음식")+": "+g.joined()+" (음식점마다 메뉴는 달라요)").orElse(""):"")
            // 확인된 음식점이 모자라 이미 들른 곳을 다시 넣었다면 숨기지 않고 알린다.
            +(!fresh?"\n확인된 음식점 후보가 적어 앞에서 들른 곳을 다시 넣었어요. 장소 목록에서 후보를 더 찾은 뒤 다시 만들 수 있어요.":"");
        builder.visit(restaurant,"MEAL",60,mealNote);
        if(dinner) builder.dinner=true; else builder.lunch=true;
    }

    /**
     * 숙박하는 날의 마무리. 숙소에 도착해 쉬었다가 18시 무렵 숙소에서 가까운 음식점에서 저녁을 먹는다.
     * 관광이 늦게 끝나 이미 저녁을 넣었거나, 저녁이 저녁 한도(22시, 더 늦게 정했으면 그 시간)까지 끝나지 않으면 저녁은 넣지 않는다.
     * 숙소 도착과 저녁은 하루 종료 시간(관광을 마치는 시간) 뒤에 올 수 있다.
     */
    private void arriveAndDine(DayBuilder builder,PlaceCandidate stay,String note) {
        // 관광은 하루 종료 시간 안에 마치고, 숙소로 가는 이동부터는 저녁 한도까지 둘 수 있다.
        builder.limit=TripConstraints.eveningLimit(builder.end);
        builder.move(stay);
        PlaceCandidate restaurant=null; int rest=30;
        if(!builder.dinner) {
            var candidate=pickRestaurant(builder,stay);
            int toDinner=builder.travelMinutes(stay,candidate);
            var dinnerStart=builder.time.plusMinutes(rest+toDinner);
            if(dinnerStart.isBefore(TripConstraints.STAY_DINNER_AT)) dinnerStart=TripConstraints.STAY_DINNER_AT;
            // 저녁이 자정을 넘기거나 저녁 한도보다 늦게 끝나면 넣지 않는다.
            var dinnerEnd=dinnerStart.plusMinutes(60);
            if(dinnerEnd.isAfter(dinnerStart) && !dinnerEnd.isAfter(builder.limit)) {
                restaurant=candidate;
                rest=(int)Duration.between(builder.time,dinnerStart).toMinutes()-toDinner;
            }
        }
        builder.add("STAY",stay,stay.name(),rest,note,null,null);
        if(restaurant==null) return;
        var beforeDinner=builder.mark();
        try { eat(builder,stay,true,restaurant); }
        catch(RecommendationException error) {
            // 운영시간이 맞지 않는 등으로 저녁이 들어가지 않으면 숙소 휴식으로 마친다.
            if(!DAY_FULL_CODES.contains(error.getCode())) throw error;
            builder.reset(beforeDinner);
        }
    }

    /**
     * 지금 상태에서 하루를 마무리(점심이 아직이면 점심, 숙박이면 숙소 도착)해 보고 되돌린다.
     * 종료 시간을 넘거나 운영시간과 맞지 않으면 예외가 나므로, 관광지를 더 넣어도 되는지 판단하는 데 쓴다.
     * 이때 조회한 이동시간은 routes에 남아 실제로 마무리할 때 다시 조회하지 않는다.
     */
    private void checkClosing(DayBuilder builder,PlaceCandidate last,PlaceCandidate stay) {
        var mark=builder.mark();
        try {
            if(!builder.lunch) eat(builder,last,false);
            // 숙소 도착은 하루 종료 시간이 아니라 저녁 한도까지면 된다(arriveAndDine과 같은 규칙).
            if(stay!=null) { builder.limit=TripConstraints.eveningLimit(builder.end); builder.visit(stay,"STAY",30,""); }
        } finally { builder.reset(mark); builder.limit=builder.end; }
    }

    /**
     * 숙박하는 날 숙소 근처에서 저녁을 먹은 뒤 저녁 관광 한 곳(야경·산책·야시장 등)을 넣고 숙소로 돌아온다.
     * 한국관광공사 야간관광 실태조사(2022)에서 많이 한 활동이 야간 산책·식도락·도시야경·야시장이라 그런 분류의 후보만 쓴다.
     * 식당에서 가까운 곳부터 세 곳까지 넣어 보고, 오가는 시간이 길거나 운영시간·저녁 한도와 맞지 않으면 넣지 않는다.
     */
    private boolean addEvening(DayBuilder builder,PlaceCandidate stay,List<Day> days,Map<String,KakaoMapClient.Location> addresses) {
        // 숙소 근처 저녁을 먹은 날만 넣는다. 관광 중에 저녁을 먹었거나 저녁이 들어가지 않은 날은 숙소에서 쉬며 마친다.
        if(builder.items.isEmpty() || !"MEAL".equals(builder.items.getLast().type())) return false;
        Set<Long> visited=new HashSet<>();
        days.forEach(d->d.items().forEach(i->{ if("VISIT".equals(i.type())) visited.add(i.placeId()); }));
        builder.items.forEach(i->{ if("VISIT".equals(i.type())) visited.add(i.placeId()); });
        var from=builder.previous;
        var fromAt=builder.locations.get(from.placeId());
        var choices=new ArrayList<PlaceCandidate>();
        for(var place:builder.input.evening()) {
            if(visited.contains(place.placeId())) continue;
            checkDeadline(builder.deadline);
            try { builder.locations.computeIfAbsent(place.placeId(),id->locate(place,addresses)); choices.add(place); }
            catch(RecommendationException error) { if("SCHEDULE_TIMEOUT".equals(error.getCode())) throw error; }
        }
        choices.sort(order(builder.date,p->distance(fromAt,builder.locations.get(p.placeId()))));
        for(var place:choices.stream().limit(3).toList()) {
            var mark=builder.mark();
            try {
                if(builder.travelMinutes(from,place)>EVENING_MAX_MOVE || builder.travelMinutes(place,stay)>EVENING_MAX_MOVE) continue;
                builder.visit(place,"VISIT",EVENING_MINUTES,eveningNote(place,from));
                builder.visit(stay,"STAY",RETURN_REST_MINUTES,"저녁 관광 뒤 숙소 복귀·휴식");
                return true;
            } catch(RecommendationException error) {
                if(!DAY_FULL_CODES.contains(error.getCode()) && !UNREACHABLE_CODES.contains(error.getCode())
                    && !"MAP_SAME_LOCATION".equals(error.getCode())) throw error;
                builder.reset(mark);
            }
        }
        return false;
    }

    private static String eveningNote(PlaceCandidate place,PlaceCandidate from) {
        return "저녁 관광"+(place.subtypeLabel().isEmpty()?"":" · "+place.subtypeLabel())
            +"\n고른 이유: 저녁 식사 뒤 산책·야경을 보기 좋은 종류라 "+from.name()+"에서 가까운 곳으로 골랐어요."
            +"\n밤에도 둘러볼 수 있는지(개방 시간·조명)는 방문 전 확인해 주세요."
            +(place.religiousSite()?"\n종교 시설이라 행사 중에는 관람이 제한될 수 있어요.":"");
    }

    /**
     * 11시가 지났고, 다음 관광지까지 가서 체류를 마치면 13시 30분을 넘기면 true다.
     * 하루를 11시 넘어 시작해 첫 관광지를 마치면 13시 30분이 넘는 경우(첫날 늦게 도착)도 점심을 먼저 먹는다.
     */
    private boolean lunchFirst(DayBuilder builder,PlaceCandidate next) {
        if(builder.time.isBefore(LUNCH_FROM)) return false;
        if(builder.previous==null) return builder.time.plusMinutes(builder.input.visitMinutes()).isAfter(LUNCH_UNTIL);
        var end=builder.time.plusMinutes(builder.travelMinutes(builder.previous,next)+builder.input.visitMinutes());
        return end.isBefore(builder.time) || end.isAfter(LUNCH_UNTIL);
    }

    /** 위치를 확인한 예비 관광지. 위치를 찾지 못한 곳은 예비에서 뺀다. */
    private List<PlaceCandidate> locatedReserve(List<PlaceCandidate> reserve,Map<Long,KakaoMapClient.Location> locations,
                                                Map<String,KakaoMapClient.Location> addresses,Instant deadline) {
        for(var place:List.copyOf(reserve)) {
            if(locations.containsKey(place.placeId())) continue;
            checkDeadline(deadline);
            try { locations.put(place.placeId(),locate(place,addresses)); }
            catch(RecommendationException error) { if("SCHEDULE_TIMEOUT".equals(error.getCode())) throw error; reserve.remove(place); }
        }
        return List.copyOf(reserve);
    }

    /**
     * 예비 관광지 하나를 꺼내 위치를 확인한다. 남은 관광지·이미 넣은 관광지와 종류가 다른 곳을 먼저 고른다.
     * 위치를 확인하지 못한 예비 관광지는 건너뛴다. 쓸 수 있는 예비 관광지가 없으면 null이다.
     */
    private PlaceCandidate takeReserve(List<PlaceCandidate> reserve,List<PlaceCandidate> remaining,List<Day> days,DayBuilder today,
                                       Map<Long,KakaoMapClient.Location> locations,Map<String,KakaoMapClient.Location> addresses,Instant deadline) {
        Set<String> kinds=new HashSet<>();
        remaining.forEach(p->kinds.add(TripConstraints.kind(p)));
        var visits=new ArrayList<Long>();
        days.forEach(d->d.items().forEach(i->{ if("VISIT".equals(i.type())) visits.add(i.placeId()); }));
        today.items.forEach(i->{ if("VISIT".equals(i.type())) visits.add(i.placeId()); });
        today.input.attractionsWithReserve().stream().filter(p->visits.contains(p.placeId())).forEach(p->kinds.add(TripConstraints.kind(p)));
        while(!reserve.isEmpty()) {
            var pick=reserve.stream().filter(p->!kinds.contains(TripConstraints.kind(p))).findFirst().orElse(reserve.getFirst());
            reserve.remove(pick);
            checkDeadline(deadline);
            try { locations.put(pick.placeId(),locate(pick,addresses)); return pick; }
            catch(RecommendationException error) { if("SCHEDULE_TIMEOUT".equals(error.getCode())) throw error; }
        }
        return null;
    }

    /** 시간이 부족해도 빼면 안 되는 관광지: 필수 방문 장소와 수정안에서 유지·새로 넣은 장소. */
    private static Set<Long> mustVisit(Input input) {
        Set<Long> ids=new HashSet<>();
        if(input.constraints()!=null) ids.addAll(input.constraints().requiredPlaceIds());
        if(input.revision()!=null) {
            // "○○도 넣어 줘"처럼 더하기만 한 수정안은 더한 곳이 먼저이고, 시간이 모자라면 기존 관광지 일부를 뺄 수 있다.
            if(!addOnly(input.revision())) input.revision().kept().forEach(p->ids.add(p.placeId()));
            input.revision().added().forEach(p->ids.add(p.placeId()));
        }
        return ids;
    }
    /** 기존 관광지를 바꾸지 않고 더하기만 한 수정안인지. */
    static boolean addOnly(Revision revision) {
        return revision.replaced().isEmpty() && !revision.added().isEmpty();
    }

    // 카카오 후보는 조회해 둔 좌표를, 그 밖에는 도로명 주소로 좌표를 찾는다. 같은 건물 주소는 한 번만 조회한다.
    private KakaoMapClient.Location locate(PlaceCandidate place,Map<String,KakaoMapClient.Location> addresses) {
        if("KAKAO".equals(place.sourceProvider()) && place.longitude()!=null && place.latitude()!=null) {
            if(place.verifiedAt()==null || place.verifiedAt().isBefore(KoreaTime.now().minusHours(24)))
                throw failure("PLACE_STALE","장소 조회 결과가 오래됐어요. 장소 목록에서 다시 조회해 주세요.");
            return new KakaoMapClient.Location(place.address(),place.longitude(),place.latitude(),place.verifiedAt().atZone(KoreaTime.ZONE).toInstant());
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
        return rankLodging(places,locations,null);
    }

    /**
     * stayTier(숙소 예산대, SFR-03-03)를 골랐으면 그 유형의 숙소를 먼저 두고, 없으면 전체 후보에서 고른다.
     * 예산대는 숙소 유형 기준이며 요금을 비교한 것이 아니다.
     */
    static Lodging rankLodging(PlaceCandidateView places,Map<Long,KakaoMapClient.Location> locations,String stayTier) {
        var ranked=rankByDistance(places,locations);
        if(ranked==null || stayTier==null) return ranked;
        var tier=BudgetGuide.StayTier.valueOf(stayTier);
        Set<Long> matching=new HashSet<>();
        for(var stay:places.accommodations())
            if(BudgetGuide.StayTier.of(stay.subtype(),stay.name()).filter(tier::equals).isPresent()) matching.add(stay.placeId());
        // 전체 순위를 다시 계산하지 않고, 같은 거리 순서 안에서 예산대에 맞는 숙소를 앞으로 옮긴다.
        var all=rankByDistance(places,locations,Integer.MAX_VALUE);
        var preferred=all.stream().filter(o->matching.contains(o.placeId())).toList();
        if(preferred.isEmpty())
            return new Lodging(ranked.chosenPlaceId(),"고른 숙소 예산대("+tier.getLabel()+")의 숙소가 후보에 없어 전체 후보에서 골랐어요. "+ranked.reason(),ranked.options());
        List<StayOption> ordered=new ArrayList<>(preferred);
        all.stream().filter(o->!matching.contains(o.placeId())).forEach(ordered::add);
        var best=ordered.getFirst();
        String reason="고른 숙소 예산대("+tier.getLabel()+") 숙소 "+preferred.size()+"곳 중 방문할 관광지 "+places.attractions().size()
            +"곳과의 평균 직선거리가 약 "+best.averageKilometers()+"km로 가장 가까워요.";
        return new Lodging(best.placeId(),reason,ordered.subList(0,Math.min(3,ordered.size())));
    }

    private static List<StayOption> rankByDistance(PlaceCandidateView places,Map<Long,KakaoMapClient.Location> locations,int limit) {
        List<StayOption> options=new ArrayList<>();
        for(var stay:places.accommodations()) {
            var at=locations.get(stay.placeId());
            if(at==null) continue;
            double total=0;
            for(var attraction:places.attractions()) total+=kilometers(at,locations.get(attraction.placeId()));
            double average=Math.round(total/places.attractions().size()*10)/10.0;
            options.add(new StayOption(stay.placeId(),stay.name(),stay.subtype(),stay.address(),average,stay.sourceUrl(),stay.mapSearchUrl()));
        }
        options.sort(Comparator.comparingDouble(StayOption::averageKilometers));
        return options.subList(0,Math.min(limit,options.size()));
    }

    // 예산대를 고르지 않았을 때: 거리 순서 그대로 첫 숙소를 쓴다. 위치를 확인한 후보가 없으면 null이다.
    private static Lodging rankByDistance(PlaceCandidateView places,Map<Long,KakaoMapClient.Location> locations) {
        var options=rankByDistance(places,locations,Integer.MAX_VALUE);
        if(options.isEmpty()) return null;
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
        if(c.durationDays()>p.attractions().size() || p.attractions().isEmpty() || p.attractions().size()>TripConstraints.MAX_TOTAL)
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
        checkHome(input);
    }

    /** 집에서 오가는 시간을 빼고도 첫날·마지막 날 관광 시간이 남는지 본다. 당일치기는 4시간, 여러 날이면 첫날·마지막 날 각 2시간 이상이다. */
    private static void checkHome(Input input) {
        var home=input.home();
        if(home==null) return;
        if(home.departAt()==null || home.arriveBy()==null || home.minutes()<1 || home.minutes()>600 || home.returnMinutes()<1 || home.returnMinutes()>600
                || home.origin()==null || home.origin().isBlank() || home.origin().length()>40 || home.returnPlace().length()>40 || home.basis()==null)
            throw failure("HOME_REQUIRED","집 출발 시각·귀가 시각과 여행지까지의 이동시간을 다시 확인해 주세요.");
        int days=input.conditions().durationDays();
        long first=Duration.between(input.dayStart(1),input.dayEnd(1)).toMinutes();
        long last=Duration.between(input.dayStart(days),input.dayEnd(days)).toMinutes();
        if(!input.dayStart(1).isBefore(input.dayEnd(1)) || !input.dayStart(days).isBefore(input.dayEnd(days)))
            first=last=0;
        if(days==1 && first<240)
            throw failure("HOME_HOURS","집에서 오가는 시간(가는 길 약 "+home.minutes()+"분, 오는 길 약 "+home.returnMinutes()+"분)을 빼면 여행지에서 보낼 시간이 4시간보다 짧아요("
                +input.dayStart(1)+"~"+input.dayEnd(1)+"). 집 출발을 앞당기거나 귀가 시각을 늦춰 주세요.");
        if(days>1 && first<120)
            throw failure("HOME_HOURS","첫날 여행지 도착("+input.dayStart(1)+") 뒤 관광을 마칠 시간("+input.dayEnd(1)+")까지 2시간이 안 돼요. 집 출발을 앞당기거나 하루 종료 시간을 늦춰 주세요.");
        if(days>1 && last<120)
            throw failure("HOME_HOURS","마지막 날 "+home.arriveBy()+"까지 집에 도착하려면 "+input.dayEnd(days)+"에 여행지를 떠나야 해서 관광할 시간이 2시간이 안 돼요. 귀가 시각을 늦춰 주세요.");
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
        // 예비 관광지는 빠진 자리를 채울 때만 방문으로 들어간다.
        Set<Long> reserveIds=new HashSet<>(); input.reserve().forEach(p->reserveIds.add(p.placeId()));
        allowed.addAll(reserveIds);
        input.places().restaurants().forEach(p->allowed.add(p.placeId()));
        input.places().accommodations().forEach(p->allowed.add(p.placeId()));
        // 저녁 관광 후보는 숙박하는 날 숙소 도착 뒤에만 방문으로 들어간다.
        Set<Long> eveningIds=new HashSet<>(); input.evening().forEach(p->eveningIds.add(p.placeId()));
        allowed.addAll(eveningIds);
        // 숙소는 첫 후보로 고정하지 않고 동선에 맞춰 고르므로, 첫날 마지막 숙소를 기준으로 모든 날이 같은 숙소인지 본다.
        Long hotelId=null;
        if(input.places().accommodationNeeded() && result.days().size()>1) {
            var firstDay=result.days().getFirst().items();
            int stayAt=stayIndex(firstDay);
            if(stayAt<0) throw invalid();
            hotelId=firstDay.get(stayAt).placeId();
            if(result.lodging()!=null && !hotelId.equals(result.lodging().chosenPlaceId())) throw invalid();
            // 수정 요청으로 숙소를 정했으면 그 숙소여야 한다(위치를 확인하지 못한 경우만 다른 숙소가 될 수 있다).
            var chosenStay=input.choices().stayPlaceId();
            if(chosenStay!=null && !chosenStay.equals(hotelId) && result.lodging()!=null && result.lodging().options().stream().anyMatch(o->o.placeId().equals(chosenStay)))
                throw invalid();
        }
        int dayNumber=0;
        for(var day:result.days()) {
            if(day.number()!=++dayNumber || day.items().isEmpty()) throw invalid();
            LocalDate date=input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(dayNumber-1);
            if(!Objects.equals(date,day.date())) throw invalid();
            LocalTime end=input.dayStart(dayNumber), dayEnd=input.dayEnd(dayNumber);
            Long previous=dayNumber>1?hotelId:null;
            Long transferDestination=null; boolean transferred=false; int meals=0, visits=0, eveningVisits=0;
            boolean stayDay=input.places().accommodationNeeded() && dayNumber<result.days().size();
            boolean lastDay=dayNumber==result.days().size();
            var items=new ArrayList<>(day.items());
            // 집 이동(첫날 맨 앞, 마지막 날 맨 뒤)은 장소 없는 이동 항목이다. 시각만 확인하고 장소 순서 검사에서는 뺀다.
            if(input.home()!=null && dayNumber==1) {
                var leg=items.isEmpty()?null:items.removeFirst();
                if(leg==null || !homeLeg(leg) || !leg.start().equals(input.home().departAt()) || !leg.end().equals(end)) throw invalid();
            }
            if(input.home()!=null && lastDay) {
                var leg=items.isEmpty()?null:items.removeLast();
                if(leg==null || !homeLeg(leg) || leg.start().isAfter(dayEnd) || leg.end().isAfter(input.home().arriveBy())
                    || Duration.between(leg.start(),leg.end()).toMinutes()!=input.home().returnMinutes()+HomeTrip.SPARE_MINUTES) throw invalid();
            }
            if(items.isEmpty()) throw invalid();
            // 숙박하는 날은 숙소로 가는 이동부터 저녁 한도(22시)를 쓴다. 관광은 그날 관광을 마치는 시각 안이어야 한다.
            int stayAt=stayDay?stayIndex(items):-1;
            int eveningFrom=eveningFrom(items,stayAt);
            int index=0;
            for(var item:items) {
                boolean evening=stayAt>=0 && index>=eveningFrom;
                LocalTime limit=evening?TripConstraints.eveningLimit(dayEnd):dayEnd;
                boolean afterStay=stayAt>=0 && index>stayAt;
                index++;
                if(item.start()==null || item.end()==null || !item.start().equals(end) || !item.end().isAfter(item.start())
                        || item.end().isAfter(limit) || item.placeId()==null || !allowed.contains(item.placeId())) throw invalid();
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
                        case "VISIT" -> {
                            // 숙소 도착 뒤의 방문은 저녁 관광이다. 저녁 관광 후보에서만, 정해진 시간만큼 들른다.
                            if(afterStay) { if(!eveningIds.contains(item.placeId()) || !seen.add(item.placeId()) || minutes!=EVENING_MINUTES) throw invalid(); eveningVisits++; }
                            else { if(!(expected.contains(item.placeId()) || reserveIds.contains(item.placeId())) || !seen.add(item.placeId()) || minutes!=input.visitMinutes()) throw invalid(); visits++; }
                        }
                        case "MEAL" -> {
                            if(input.places().restaurants().stream().noneMatch(p->p.placeId().equals(item.placeId()))) throw invalid();
                            // 빼 달라고 한 식당은 다른 식당 후보가 하나라도 있으면 쓰지 않는다.
                            if(input.choices().avoidRestaurants().contains(item.placeId())
                                && input.places().restaurants().stream().anyMatch(p->!input.choices().avoidRestaurants().contains(p.placeId()))) throw invalid();
                            meals++;
                        }
                        case "STAY" -> { if(input.places().accommodations().stream().noneMatch(p->p.placeId().equals(item.placeId()))
                            || (hotelId!=null && !hotelId.equals(item.placeId()))) throw invalid(); }
                        case "FREE" -> { }
                        default -> throw invalid();
                    }
                    if(Set.of("VISIT","MEAL","STAY").contains(item.type())) {
                        var pool=new ArrayList<>(input.attractionsWithReserve()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
                        var place=pool.stream().filter(p->p.placeId().equals(item.placeId())).findFirst().orElseThrow(ItineraryGenerator::invalid);
                        OperatingHoursValidator.check(place.name(),place.operatingInfo(),date,item.start(),item.end());
                    }
                }
                end=item.end();
            }
            // 식사는 하루 점심 1번, 종료 시간이 늦은 날이나 숙박하는 날은 저녁까지 최대 2번이다.
            if(meals<1 || meals>(TripConstraints.includesDinner(dayEnd) || stayDay?2:1) || visits==0 || transferred) throw invalid();
            // 하루 방문 수는 저녁 관광까지 센다. 정한 수를 넘으면 안 되고, 필수 조건이면 매일 그 수를 지켜야 한다.
            if(constraints!=null && constraints.dailyVisits()!=null && visits+eveningVisits>constraints.dailyVisits()) throw invalid();
            if(constraints!=null && constraints.dailyVisitsRequired() && visits+eveningVisits!=constraints.dailyVisits()) throw invalid();
            // 숙박하는 날은 숙소 도착이 있어야 하고, 그 뒤에는 숙소 근처 저녁과 저녁 관광 한 곳(다녀와 숙소 복귀)만 올 수 있다.
            if(stayDay && (stayAt<0 || !Objects.equals(hotelId,items.get(stayAt).placeId())
                    || !afterStayIsEveningOnly(items,stayAt))) throw invalid();
        }
        // 배치하지 못한 관광지는 결과의 skipped와 정확히 같아야 하고, 필수·유지 장소는 빠지면 안 된다.
        // 고른 관광지 중 넣지 못한 곳은 모두 skipped에 있어야 하고, skipped에는 방문하지 않은 관광지(예비 포함)만 있어야 한다.
        Set<Long> skipped=new HashSet<>(); result.skipped().forEach(p->skipped.add(p.placeId()));
        Set<Long> missing=new HashSet<>(expected); missing.removeAll(seen);
        Set<Long> notVisited=new HashSet<>(expected); notVisited.addAll(reserveIds); notVisited.removeAll(seen);
        // 저녁 관광으로 들른 곳이 고른 관광지나 예비 관광지와 겹치면 방문한 것으로 본다(seen에 이미 들어 있다).
        if(!skipped.containsAll(missing) || !notVisited.containsAll(skipped) || skipped.stream().anyMatch(mustVisit(input)::contains)) throw invalid();
    }

    /** 일정 조건의 중심 장소 중 배치 대상에 있는 곳. 여행 일수만큼만 쓴다(수정안에서 바꾼 중심 장소는 빠진다). */
    private static List<PlaceCandidate> centerPlaces(Input input,int dayCount) {
        if(input.constraints()==null) return List.of();
        return input.constraints().centerPlaceIds().stream()
            .map(id->input.places().attractions().stream().filter(p->p.placeId().equals(id)).findFirst().orElse(null))
            .filter(Objects::nonNull).limit(dayCount).toList();
    }

    /**
     * 중심 장소 중 앞에서 이미 날을 받은 곳과 SAME_DAY_CENTER_KM 안에 있는 곳은 따로 날을 주지 않는다.
     * 그런 곳은 일반 관광지로 남아 가장 가까운 중심 장소의 날에 먼저 들어간다.
     */
    private static List<PlaceCandidate> separateCenters(List<PlaceCandidate> centers,Map<Long,KakaoMapClient.Location> locations) {
        var kept=new ArrayList<PlaceCandidate>();
        for(var center:centers)
            if(kept.stream().noneMatch(k->kilometers(locations.get(k.placeId()),locations.get(center.placeId()))<SAME_DAY_CENTER_KM))
                kept.add(center);
        return kept;
    }

    /** 하루 항목 중 첫 숙소 도착의 위치. 없으면 -1이다. 저녁 관광 뒤 숙소 복귀는 두 번째 숙소 항목이다. */
    static int stayIndex(List<Item> items) {
        for(int i=0;i<items.size();i++) if("STAY".equals(items.get(i).type())) return i;
        return -1;
    }

    /** 저녁 한도를 쓰기 시작하는 항목 위치: 숙소 도착 바로 앞의 이동(없으면 숙소 도착)이다. 숙소 도착이 없으면 -1이다. */
    static int eveningFrom(List<Item> items,int stayAt) {
        if(stayAt<0) return -1;
        return stayAt>0 && "TRANSIT".equals(items.get(stayAt-1).type())?stayAt-1:stayAt;
    }

    /** 첫날 집 출발·마지막 날 귀가 이동 항목(장소 없음)인지 본다. */
    static boolean homeLeg(Item item) {
        return "TRANSIT".equals(item.type()) && item.placeId()==null && item.start()!=null && item.end()!=null && item.end().isAfter(item.start());
    }

    /**
     * 숙소 도착 뒤에는 이동을 빼고 [저녁 식사] 다음 [저녁 관광 → 숙소 복귀]만 올 수 있다(둘 다 없어도 된다).
     * 저녁 관광은 숙소 근처에서 저녁을 먹은 날에만 넣는다.
     */
    static boolean afterStayIsEveningOnly(List<Item> items,int stayAt) {
        var after=items.subList(stayAt+1,items.size());
        if(!after.isEmpty() && "TRANSIT".equals(after.getLast().type())) return false;
        var kinds=after.stream().map(Item::type).filter(t->!"TRANSIT".equals(t)).toList();
        return kinds.isEmpty() || kinds.equals(List.of("MEAL")) || kinds.equals(List.of("MEAL","VISIT","STAY"));
    }

    private class DayBuilder {
        final Input input; final Map<Long,KakaoMapClient.Location> locations;
        final Map<String,KakaoMapClient.TravelTime> routes; final Instant deadline;
        final List<Item> items=new ArrayList<>(); LocalTime time; PlaceCandidate previous; boolean lunch, dinner;
        /** 항목이 끝나야 하는 시각. 보통은 그날 관광을 마치는 시각(end)이고, 숙박하는 날 숙소로 가는 이동부터는 저녁 한도(22시)다. */
        LocalTime limit;
        /** 그날 관광을 마치는 시각. 마지막 날 귀가 시각을 정했으면 집에 제때 도착하도록 앞당겨진다. */
        final LocalTime end;
        final LocalDate date; final Set<Long> usedRestaurants; final Optional<RegionalFoods.Guide> foods;
        DayBuilder(Input input,Map<Long,KakaoMapClient.Location> locations,Map<String,KakaoMapClient.TravelTime> routes,Instant deadline,
                   PlaceCandidate previous,int day,Set<Long> usedRestaurants,Optional<RegionalFoods.Guide> foods) {
            this.input=input; this.locations=locations; this.routes=routes; this.deadline=deadline; this.previous=previous;
            time=input.dayStart(day); end=input.dayEnd(day); limit=end;
            date=input.conditions().startDate()==null?null:input.conditions().startDate().plusDays(day-1);
            this.usedRestaurants=usedRestaurants; this.foods=foods;
        }
        /** 관광지나 식사를 시험 삼아 넣어 보기 전의 상태. 들어가지 않으면 reset으로 되돌린다. */
        record Mark(int size,LocalTime time,PlaceCandidate previous,boolean lunch,boolean dinner,Set<Long> usedRestaurants) {}
        Mark mark() { return new Mark(items.size(),time,previous,lunch,dinner,Set.copyOf(usedRestaurants)); }
        void reset(Mark mark) {
            items.subList(mark.size(),items.size()).clear();
            time=mark.time(); previous=mark.previous(); lunch=mark.lunch(); dinner=mark.dinner();
            usedRestaurants.clear(); usedRestaurants.addAll(mark.usedRestaurants());
        }
        /** 첫날 맨 앞: 집 → 여행지 이동. 편도 이동시간과 여유 10분을 더해 10분 단위로 올린 도착 시각까지다. */
        void homeDeparture() {
            var home=input.home();
            time=home.departAt();
            addHome(home.origin()+" → "+input.places().destination().name(),home.arrivalAtDestination(),
                "편도 약 "+home.minutes()+"분 + 이동 여유 "+HomeTrip.SPARE_MINUTES+"분 ("+home.basis()+")"
                    +"\n도착한 뒤 첫 관광지로 바로 가요. 여행지 안 첫 장소까지의 이동은 차이가 있을 수 있어요.");
        }
        /** 마지막 날 맨 뒤: 마지막 장소 → 돌아갈 곳 이동. 편도 이동시간과 여유 10분이다. */
        void homeReturn() {
            var home=input.home();
            limit=home.arriveBy();
            addHome((previous==null?input.places().destination().name():previous.name())+" → "+home.returnPlace(),
                time.plusMinutes(home.returnMinutes()+HomeTrip.SPARE_MINUTES),
                "편도 약 "+home.returnMinutes()+"분 + 이동 여유 "+HomeTrip.SPARE_MINUTES+"분 ("+home.returnBasis()+")"
                    +"\n"+home.arriveBy()+"까지 "+home.returnPlace()+"에 도착하도록 관광을 마쳤어요.");
        }
        // 집은 장소 후보가 아니라 place_id 없이 이동 항목으로만 둔다. 출발지 좌표는 저장하지 않아 지도 링크도 없다.
        private void addHome(String name,LocalTime until,String note) {
            int minutes=(int)Duration.between(time,until).toMinutes();
            if(minutes<=0 || until.isAfter(limit))
                throw failure("TIME_OVERLAP",previous==null
                    ?"집에서 출발해 여행지에 도착하면 첫날 관광을 마칠 시간이 지나요. 집 출발 시각을 앞당겨 주세요."
                    :"정한 귀가 시각까지 집에 돌아갈 시간이 모자라요. 귀가 시각을 늦춰 주세요.");
            items.add(new Item("TRANSIT",null,name,time,until,note,null,null,null,input.home().checkedAt()));
            time=until;
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
                    // 가까워서 도보로 추정한 구간은 카카오 예상값이 아니므로 따로 표시한다.
                    add("TRANSIT",next,previous.name()+" → "+next.name(),route.minutes()+10,
                        (route.walkingEstimate()?WALK_NOTE_PREFIX:"카카오 예상 ")+route.minutes()+"분 + 이동 여유 10분"
                            +(route.summary()==null?"":"\n"+route.summary()
                                +(route.walkingEstimate()?"":" (조회 시점 기준, 배차·요금은 이용 전 확인)")),
                        route.seconds(),route.checkedAt());
                }
            }
            previous=next;
        }
        /** move와 같은 규칙으로 계산한 이동 분(여유 10분 포함). 조회 결과는 routes에 남아 실제 이동 때 다시 조회하지 않는다. */
        int travelMinutes(PlaceCandidate from,PlaceCandidate to) {
            if(from.placeId().equals(to.placeId())) return 0;
            var a=locations.get(from.placeId()); var b=locations.get(to.placeId());
            if(a.longitude()==b.longitude() && a.latitude()==b.latitude()) return 5;
            return routes.computeIfAbsent(from.placeId()+":"+to.placeId(),key->maps.travelTime(a,b,input.transport())).minutes()+10;
        }
        void add(String type,PlaceCandidate place,String name,int minutes,String note,Integer seconds,Instant checkedAt) {
            if(minutes<=0 || time.toSecondOfDay()+minutes*60>limit.toSecondOfDay())
                throw failure("TIME_OVERLAP","방문·식사·이동 시간이 하루 종료 시간을 넘어요. 이용 시간을 늘리거나 관광지 체류시간을 줄여 주세요.");
            var location=locations.get(place.placeId());
            // 운영정보를 조회한 곳만 항목에 판단 결과를 적는다. 정보가 없는 곳은 조건 확인 결과에 모아서 한 번 알린다.
            if(Set.of("VISIT","MEAL","STAY").contains(type) && place.operatingInfo()!=null) note += "\n" + OperatingHoursValidator.note(place.name(),place.operatingInfo(),date,time,time.plusMinutes(minutes));
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
