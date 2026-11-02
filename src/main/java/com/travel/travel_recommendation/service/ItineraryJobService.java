package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.*;
import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class ItineraryJobService {
    private final RecommendationJobService access;
    private final PlaceCandidateService candidates;
    private final AiJobRepository jobs;
    private final UserRepository users;
    private final PlaceRepository places;
    private final ItineraryGenerator generator;
    private final ItineraryAssistant assistant;
    private final OpenAiRevisionClient revisions;
    private final NearbyRegionService nearby;
    private final OpenAiPriceClient priceClient;
    // 트랜잭션이 걸린 공개 메서드를 같은 객체 안에서 부를 때 프록시를 거치게 한다.
    private final org.springframework.beans.factory.ObjectProvider<ItineraryJobService> selfProvider;
    private final ObjectMapper mapper;
    // 둘 다 버스 안내·장소 수집 서비스와 서로를 참조하지 않도록 필요할 때 꺼내 쓴다.
    private final org.springframework.beans.factory.ObjectProvider<HomeTripService> homeTrips;
    private final org.springframework.beans.factory.ObjectProvider<PlaceDiscoveryService> discoveries;
    // 반려동물 동반 정보는 SQL Server 프로필에서만 조회한다(장소 상세정보 보관).
    private final org.springframework.beans.factory.ObjectProvider<PlacePetService> pets;
    // 관광공사 장소 소개문도 장소 상세정보에 보관하므로 SQL Server 프로필에서만 조회한다.
    private final org.springframework.beans.factory.ObjectProvider<PlaceOverviewService> placeOverviews;
    // 확인 필요 항목 AI 웹 검색. 설정(travel.verification.auto)에 따라 초안을 만들 때 자동으로 돌리거나 버튼으로만 찾는다.
    private final OpenAiVerificationClient verificationClient;
    private final com.travel.travel_recommendation.config.VerificationProperties verification;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(ItineraryJobService.class);
    public record Work(UUID id,Input input) {}

    @Transactional(readOnly=true,timeout=10)
    public Input options(UUID parentId,long destinationId,String login,byte[] hash) {
        // 관광지 후보는 전부 돌려준다. 몇 곳을 쓸지는 create에서 하루 시간을 보고 TripConstraints.select로 정한다.
        var pool=candidates.find(parentId,destinationId,login,hash);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        var conditions=mapper.readValue(parent.getRequestJson(),ConfirmedTravelConditions.class);
        var transport=confirmedTransport(conditions);
        return new Input(parentId,pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),
            transport==null?KakaoMapClient.Transport.CAR:transport,90);
    }

    /** 이 여행지에 있는 여행 목적 시설(예: 아쿠아리움)의 관광지 ID. 일정 화면에서 필수 방문 장소 기본값으로 쓴다. */
    @Transactional(readOnly=true,timeout=10)
    public List<Long> purposePlaceIds(UUID parentId,long destinationId,String login,byte[] hash) {
        return candidates.purpose(parentId,destinationId,login,hash).placeIds();
    }

    /**
     * 일정 만들기 화면에서 미리 골라 둘 "일정의 중심으로 삼을 곳". 추천 결과에서 GPT가 이유와 함께 적은 장소 이름과
     * 여행지 소개 문구를 실제 후보 이름과 맞춰 고른다. 여행 일수만큼(일수가 미정이면 1곳) 고른다.
     */
    @Transactional(readOnly=true,timeout=10)
    public List<Long> defaultCenterPlaceIds(UUID parentId,long destinationId,String login,byte[] hash) {
        var pool=candidates.find(parentId,destinationId,login,hash);
        var ranked=access.view(parentId,login,hash).recommendations().stream()
            .filter(r->r.destination().placeId()==destinationId).findFirst().orElse(null);
        int days=pool.durationDays()==null?1:pool.durationDays();
        return CenterPlaces.pick(pool.attractions(),pool.destination().name(),pool.destination().description(),
            ranked==null?List.of():ranked.centerPlaces(),days);
    }

    /**
     * 여행 기간에 이 여행지에서 열리는 축제 중 장소를 확인한 첫 축제. 일정의 필수 방문 장소 기본값과 방문 이유 안내에 쓴다.
     * 출발일·일수가 미정이거나 기간이 겹치는 축제가 없으면 null이다.
     */
    @Transactional(readOnly=true,timeout=10)
    public FestivalVisit festivalVisit(UUID parentId,long destinationId,String login,byte[] hash,LocalDate startDate,Integer days) {
        if(startDate==null || days==null) return null;
        var ranked=access.view(parentId,login,hash).recommendations().stream()
            .filter(r->r.destination().placeId()==destinationId).findFirst().orElse(null);
        if(ranked==null) return null;
        LocalDate endDate=startDate.plusDays(days-1);
        for(var festival:ranked.festivals()) {
            Long placeId=ranked.festivalPlaceIds().get(festival.id());
            if(placeId==null || festival.startDate().isAfter(endDate) || festival.endDate().isBefore(startDate)) continue;
            // 축제가 여행 중간에 시작하면 그날을, 이미 열리고 있으면 첫날을 축제 보는 날로 잡는다.
            LocalDate first=festival.startDate().isBefore(startDate)?startDate:festival.startDate();
            return new FestivalVisit(placeId,(int)java.time.temporal.ChronoUnit.DAYS.between(startDate,first)+1,festival.title(),festival.period());
        }
        return null;
    }

    /** 초안에 넣지 않은 여행 기간 축제 안내. placeName은 축제가 열리는 관광지 이름이다. */
    public record FestivalSuggestion(String title,String period,String placeName) {}

    /**
     * 여행 기간에 열리는 축제 장소가 이 초안에 없으면 넣는 방법을 안내한다(SFR-04-03). 바로 만든 초안·다시 만든 초안은 축제 장소를
     * 자동으로 넣지 않기 때문이다. 축제가 없거나, 이미 일정에 있거나, 입력 화면의 관광지 후보에 없어 고를 수 없으면 null이다.
     */
    @Transactional(readOnly=true,timeout=10)
    public FestivalSuggestion festivalSuggestion(Input input,Result result,String login,byte[] hash) {
        var c=input.conditions();
        long destinationId=input.places().destination().placeId();
        var festival=festivalVisit(input.recommendationId(),destinationId,login,hash,c.startDate(),c.durationDays());
        if(festival==null) return null;
        boolean placed=result.days().stream().flatMap(d->d.items().stream()).anyMatch(i->festival.placeId().equals(i.placeId()));
        if(placed) return null;
        var place=findPlace(attractionPool(input.recommendationId(),destinationId,login,hash),festival.placeId());
        return place==null?null:new FestivalSuggestion(festival.title(),festival.period(),place.name());
    }

    /** 출발일을 정하지 않았을 때 제안할 축제. 이 여행지의 축제 중 가장 먼저 열리는 것이며 없으면 null이다. */
    @Transactional(readOnly=true,timeout=10)
    public RegionalFestival firstFestival(UUID parentId,long destinationId,String login,byte[] hash) {
        return access.view(parentId,login,hash).recommendations().stream().filter(r->r.destination().placeId()==destinationId)
            .flatMap(r->r.festivals().stream()).findFirst().orElse(null);
    }

    /** 필수 방문 장소로 고를 수 있는 전체 관광지 후보(여행 일수에 따라 16~27곳). */
    @Transactional(readOnly=true,timeout=10)
    public List<PlaceCandidate> attractionPool(UUID parentId,long destinationId,String login,byte[] hash) {
        return candidates.find(parentId,destinationId,login,hash).attractions();
    }

    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes) {
        return create(parentId,destinationId,login,hash,start,end,transport,visitMinutes,null);
    }

    /**
     * 필수·선호 조건(SFR-02-07)을 반영해 일정 작업을 만든다. 필수 조건끼리 모순되면 작업을 만들지 않고
     * {@link TripConstraints.ConflictException}으로 이유와 선택지를 알린다.
     */
    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes,Constraints constraints) {
        return create(parentId,destinationId,login,hash,start,end,transport,visitMinutes,constraints,null,null);
    }

    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes,Constraints constraints,Integer durationDays,Boolean accommodationNeeded) {
        return create(parentId,destinationId,login,hash,start,end,transport,visitMinutes,constraints,durationDays,accommodationNeeded,null);
    }

    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes,Constraints constraints,Integer durationDays,Boolean accommodationNeeded,
                       LocalDate startDate) {
        return create(parentId,destinationId,login,hash,start,end,transport,visitMinutes,constraints,durationDays,accommodationNeeded,startDate,null);
    }

    /** home은 첫날 집 출발·마지막 날 귀가 설정이다(HomeTripService가 이동시간을 찾아 만든다). 없으면 여행지 안 일정만 만든다. */
    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes,Constraints constraints,Integer durationDays,Boolean accommodationNeeded,
                       LocalDate startDate,HomeTrip home) {
        return create(parentId,destinationId,login,hash,start,end,transport,visitMinutes,constraints,durationDays,accommodationNeeded,startDate,home,Set.of(),null);
    }

    /**
     * '다른 관광지로 다시 만들기'(SFR-04 일정 재생성). 보고 있던 초안(sourceId)과 같은 추천·여행지·시간·이동수단·필수 조건으로 새 초안을 만들되,
     * 그 초안에 넣었던 관광지는 후보 순서에서 뒤로 보내 이번에는 다른 곳부터 고르게 한다. 원래 초안은 그대로 둔다.
     * 같은 조건으로 다시 만들면 관광지가 73~100% 겹쳤기 때문이다(2026-10-30 측정).
     */
    @Transactional(timeout=10)
    public UUID recreate(UUID sourceId,String login,byte[] hash) {
        var source=view(sourceId,login,hash);
        if(source.result()==null || !"COMPLETED".equals(source.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"일정 초안이 완성된 뒤에 다시 만들 수 있어요.");
        var input=source.input(); var c=input.conditions();
        Set<Long> previous=source.result().days().stream().flatMap(d->d.items().stream())
            .filter(i->"VISIT".equals(i.type()) && i.placeId()!=null).map(Item::placeId).collect(java.util.stream.Collectors.toSet());
        // 결과 화면에서 이전 초안으로 돌아가고 무엇이 바뀌었는지 비교할 수 있게 이전 초안 ID(sourceId)도 남긴다.
        return create(input.recommendationId(),input.places().destination().placeId(),login,hash,input.start(),input.end(),input.transport(),
            input.visitMinutes(),input.constraints(),c.durationDays(),c.accommodationNeeded(),c.startDate(),input.home(),previous,sourceId);
    }

    /**
     * 실패한 초안을 같은 조건(하루 시간·이동수단·필수 조건·여행 정보·집 출발·귀가)으로 다시 만든다.
     * 수정안은 원래 초안에서 다시 고쳐야 하므로 여기서 다시 만들지 않는다. 실패한 초안은 그대로 둔다.
     */
    @Transactional(timeout=10)
    public UUID retry(UUID failedId,String login,byte[] hash) {
        var failed=view(failedId,login,hash);
        if(!"FAILED".equals(failed.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"만들지 못한 초안만 같은 조건으로 다시 만들 수 있어요.");
        var input=failed.input(); var c=input.conditions();
        if(input.revision()!=null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"수정안은 원래 초안 화면에서 다시 요청해 주세요.");
        // '다른 관광지로 다시 만들기'였다면 그때의 이전 초안 정보도 그대로 이어 간다.
        Set<Long> previous=input.picking()==null?Set.of():Set.copyOf(input.picking().previous());
        return create(input.recommendationId(),input.places().destination().placeId(),login,hash,input.start(),input.end(),input.transport(),
            input.visitMinutes(),input.constraints(),c.durationDays(),c.accommodationNeeded(),c.startDate(),input.home(),previous,input.previousJobId());
    }

    /**
     * previous는 직전 초안에 넣었던 관광지 ID다('다른 관광지로 다시 만들기'). 비어 있으면 보통 초안을 만든다.
     * previousJobId는 그 직전 초안의 작업 ID로, 결과 화면의 '이전 초안 보기'와 비교에 쓴다(보통 초안은 null).
     */
    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes,Constraints constraints,Integer durationDays,Boolean accommodationNeeded,
                       LocalDate startDate,HomeTrip home,Set<Long> previous,UUID previousJobId) {
        var current=options(parentId,destinationId,login,hash);
        // 추천 때 미정이던 값만 일정 생성 시 받는다. 명시된 날짜·기간을 조용히 덮어쓰지 않는다.
        var c=current.conditions();
        if(durationDays!=null && c.durationDays()!=null && !durationDays.equals(c.durationDays()))
            throw scheduleConflict("이미 입력한 여행 기간과 달라요. 추천 화면에서 조건을 수정해 주세요.");
        if(accommodationNeeded!=null && c.accommodationNeeded()!=null && !accommodationNeeded.equals(c.accommodationNeeded()))
            throw scheduleConflict("이미 입력한 숙박 조건과 달라요. 추천 화면에서 조건을 수정해 주세요.");
        Integer daysToUse=c.durationDays()==null?durationDays:c.durationDays();
        Boolean stay=c.accommodationNeeded()==null?accommodationNeeded:c.accommodationNeeded();
        if(daysToUse==null || daysToUse<1 || daysToUse>7) throw scheduleConflict("일정에 필요한 여행 일수를 1~7일로 골라 주세요.");
        if(daysToUse>1 && stay==null) throw scheduleConflict("여러 날 여행에는 숙소가 필요한지 골라 주세요.");
        if(daysToUse==1 && stay==null) stay=false;
        c=c.withSchedule(daysToUse,stay);
        // 추천 때 출발일을 정하지 않았다면 여기서 받은 출발일로 날짜를 채운다. 날짜가 있어야 일정을 저장할 수 있다.
        if(startDate!=null && c.startDate()!=null && !startDate.equals(c.startDate()))
            throw scheduleConflict("이미 입력한 출발일과 달라요. 추천 화면에서 조건을 수정해 주세요.");
        if(startDate!=null && c.startDate()==null) {
            if(startDate.isBefore(LocalDate.now(ZoneId.of("Asia/Seoul")))) throw scheduleConflict("출발일은 오늘이나 그 이후 날짜로 골라 주세요.");
            c=c.withStartDate(startDate);
        }
        // 필수 방문 장소에 여행 기간의 축제 장소가 들어 있으면 일정의 방문 이유에 축제 이름·기간을 안내하도록 표시한다.
        var festival=festivalVisit(parentId,destinationId,login,hash,c.startDate(),c.durationDays());
        if(constraints!=null && festival!=null && constraints.requiredPlaceIds().contains(festival.placeId()))
            constraints=constraints.withFestival(festival);
        current=new Input(parentId,candidates.findForSchedule(parentId,destinationId,login,hash,c),c,
            current.start(),current.end(),current.transport(),current.visitMinutes());
        var fixed=confirmedTransport(current.conditions());
        if(fixed!=null && fixed!=transport) throw new ResponseStatusException(HttpStatus.CONFLICT,"확정한 이동수단과 달라요. 여행 조건에서 먼저 수정해 주세요.");
        // 숙소 예산대는 예산을 정하지 않았고 숙소가 필요한 여행에서만 고른다(SFR-03-03).
        if(constraints!=null && constraints.stayTier()!=null && (current.conditions().budget()!=null || !current.places().accommodationNeeded()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"숙소 예산대는 예산을 정하지 않은 숙박 여행에서만 고를 수 있어요.");
        // 고른 필수·중심 장소가 후보 순서가 바뀌어 목록(개수 제한)에서 밀려났으면 지금도 확인된 장소인지 다시 읽어 후보에 더한다.
        // 확인하지 못한 곳은 임의로 빼지 않고 이름을 알려 다시 고르게 한다('다른 관광지로 다시 만들기'·하루 시간 입력 제출).
        var places=withChosenPlaces(current.places(),constraints);
        int days=current.conditions().durationDays()==null?1:current.conditions().durationDays();
        // 시간 범위 자체의 검증은 generator.checkInput이 한다. 여기서는 조건 선택에 필요한 값만 먼저 확인한다.
        if(start==null || end==null || (constraints!=null && !end.isAfter(start)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"하루 시작·종료 시간을 확인해 주세요.");
        // 하루 시간·체류시간으로 추정한 수만큼 관광지를 고른다. 실제로 다 들어가는지는 일정 생성 때 이동시간으로 다시 확인한다.
        // 일정의 중심 장소가 있으면 맨 앞에 두고 그 주변부터, 없으면 가운데에서 가까운 곳부터 고른다.
        // 같은 종류(해수욕장 등)는 돌아가며 고르고, 고른 여행 취향에 맞는 곳은 그중에서도 먼저 고른다.
        // 여기서 고른 것은 조건 충돌을 바로 알리기 위한 기본 선택이다. 작업이 시작되기 전에 AI가 순서를 정하면 그 순서로 다시 고른다(PlacePickService).
        // 사찰·성당은 사용자가 원한 경우(문화·역사 취향, 목적에 절·성당)가 아니면 한 곳만 제자리에 두고 뒤로 보낸다.
        // 사용자가 고른 필수·중심 장소와 목적 시설·축제 장소는 옮기지 않는다.
        Set<Long> keepInOrder=new HashSet<>();
        if(constraints!=null) { keepInOrder.addAll(constraints.requiredPlaceIds()); keepInOrder.addAll(constraints.centerPlaceIds()); }
        if(festival!=null) keepInOrder.add(festival.placeId());
        if(current.conditions().purposeSearch()!=null)
            current.conditions().purposeSearch().placesIn(destinationId).forEach(p->keepInOrder.add(p.placeId()));
        // 경주처럼 대표 분류가 문화·역사인 여행지는 취향을 고르지 않아도 문화유산을 먼저 고르고 사찰·성당도 뒤로 보내지 않는다.
        var destinationPlace=this.places.findById(destinationId).orElse(null);
        var orderThemes=ThemePlaces.withHeritageDestination(current.conditions().themeNames(),
            destinationPlace==null?null:destinationPlace.getName(),destinationPlace==null?null:destinationPlace.getSubtype());
        // 대릉원 안의 천마총처럼 종류가 같고 아주 가까운 곳은 후보에서부터 한 곳만 남긴다. AI가 순서를 정할 때도 이 후보(pool)를 쓴다.
        var pool=TripConstraints.withoutSameSpot(places.attractions(),keepInOrder);
        var ordered=ThemePlaces.religiousLast(TripConstraints.aroundCenters(pool,
            constraints==null?List.of():constraints.centerPlaceIds(),orderThemes),
            orderThemes,current.conditions().travelPurpose(),keepInOrder);
        // '다른 관광지로 다시 만들기'면 직전 초안의 관광지를 뒤로 보내 이번에는 다른 곳부터 고른다. 필수·중심·목적 시설·축제 장소는 그대로 둔다.
        var later=previous.stream().filter(id->!keepInOrder.contains(id)).toList();
        ordered=TripConstraints.previousLast(ordered,later,keepInOrder,Set.of());
        Arranged arranged;
        try {
            arranged=withoutPreviousEvening(arrange(ordered,Set.of(),false,places.accommodationNeeded(),days,start,end,visitMinutes,constraints),later);
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"화면에 있는 장소와 값으로 조건을 다시 골라 주세요.");
        }
        places=new PlaceCandidateView(places.jobId(),places.destination(),places.durationDays(),places.accommodationNeeded(),
            arranged.selected(),places.accommodations(),places.restaurants());
        var input=new Input(parentId,places,current.conditions(),start,end,transport,visitMinutes,null,constraints,arranged.reserve(),arranged.evening(),home)
            .withPicking(new Picking(pool,false,later,previousJobId));
        try {
            generator.checkInput(input);
        } catch(RecommendationException error) {
            // 집에서 오가는 시간 때문에 첫날·마지막 날 시간이 모자라면 입력 화면에서 이유와 고칠 방법을 보여 준다.
            if(!error.getCode().startsWith("HOME_")) throw error;
            throw new TripConstraints.ConflictException(List.of(new TripConstraints.Conflict(error.getMessage(),
                List.of("하루 시작·종료 시간 늘리기","출발지를 비워 집 이동 없이 여행지 도착부터 일정 짜기"))));
        }
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        checkActiveJobs(parent,hash);
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }
    /** 한 사람이 동시에 맡길 수 있는 일정 작업 수. 실행기가 하나라 한 사람의 작업이 줄을 채우면 다른 사람의 초안이 대기 제한에 걸린다. */
    public static final int MAX_ACTIVE_JOBS=3;
    private void checkActiveJobs(AiJob parent,byte[] hash) {
        var active=List.of("QUEUED","GENERATING_SCHEDULE");
        long count=parent.getUserId()!=null?jobs.countByUserIdAndJobTypeAndStatusIn(parent.getUserId(),"ITINERARY",active)
            :jobs.countByGuestTokenHashAndJobTypeAndStatusIn(hash,"ITINERARY",active);
        if(count>=MAX_ACTIVE_JOBS)
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"만들고 있는 일정이 많아요. 지금 만드는 일정이 끝난 뒤 다시 시도해 주세요.");
    }

    /** 일정에 넣을 관광지(selected), 예비 관광지(reserve), 저녁 관광 후보(evening). */
    public record Arranged(List<PlaceCandidate> selected,List<PlaceCandidate> reserve,List<PlaceCandidate> evening) {}

    /**
     * 줄 세운 관광지 후보(ordered)에서 저녁 관광 몫을 떼고 낮 관광지와 예비 관광지를 고른다.
     * skips는 AI가 뺄 곳으로 적은 곳이다(ordered의 맨 뒤). 저녁 관광·예비 관광지로는 쓰지 않고 낮 관광지가 모자랄 때만 쓰인다.
     * aiOrdered면 앞쪽은 낮에 갈 만한 곳이므로, 낮 관광지 수만큼 건너뛴 뒤쪽에서 저녁 관광 후보를 고른다.
     * @throws IllegalArgumentException 화면에 없는 장소·범위를 벗어난 값
     * @throws TripConstraints.ConflictException 필수 조건끼리 또는 시간·후보 수와 모순될 때
     */
    public static Arranged arrange(List<PlaceCandidate> ordered,Set<Long> skips,boolean aiOrdered,boolean stay,int days,
                                   LocalTime start,LocalTime end,int visitMinutes,Constraints constraints) {
        return arrange(ordered,skips,List.of(),aiOrdered,stay,days,start,end,visitMinutes,constraints);
    }

    /**
     * night는 AI가 밤에 둘러보기 좋다고 꼽은 관광지 ID다(2026-10-31). 숙박하는 밤 수만큼 저녁 관광 후보 맨 앞에 두고 낮 관광지에서는 뺀다.
     * AI 순서에서는 앞쪽을 낮 관광지로 쓰기 때문에, 이렇게 하지 않으면 광안리처럼 밤에 더 좋은 곳이 낮에 먼저 들어가 저녁 후보에서 빠졌다.
     */
    public static Arranged arrange(List<PlaceCandidate> ordered,Set<Long> skips,List<Long> night,boolean aiOrdered,boolean stay,int days,
                                   LocalTime start,LocalTime end,int visitMinutes,Constraints constraints) {
        // 숙박 여행이면 저녁 관광에 어울리는 곳(야경·산책·야시장 등)을 먼저 저녁 몫으로 떼어 두고 나머지로 낮 관광지를 고른다.
        // 필수·중심 장소는 낮 일정에 넣어야 하므로 떼어 두지 않는다.
        List<PlaceCandidate> evening=List.of();
        if(stay && start!=null && end!=null && end.isAfter(start)) {
            Set<Long> keepForDay=new HashSet<>();
            if(constraints!=null) { keepForDay.addAll(constraints.requiredPlaceIds()); keepForDay.addAll(constraints.centerPlaceIds()); }
            int daily=constraints!=null && constraints.dailyVisits()!=null?constraints.dailyVisits()
                :TripConstraints.estimatedDailyCapacity(start,end,visitMinutes);
            int share=TripConstraints.eveningShare(ordered.size(),days,daily);
            // AI가 꼽은 밤 명소를 먼저 저녁 몫으로 둔다. 숙박하는 밤(일수 - 1)마다 한 곳까지이고, 저녁 몫 안에서만이다.
            var nightFirst=TripConstraints.nightPlaces(ordered,night,keepForDay,skips,Math.min(days-1,share));
            var eveningSource=(!aiOrdered?ordered:ordered.stream().skip(Math.min(TripConstraints.MAX_TOTAL,(long)days*daily))
                .filter(p->!skips.contains(p.placeId())).toList()).stream().filter(p->!nightFirst.contains(p)).toList();
            var rest=TripConstraints.eveningPlaces(eveningSource,keepForDay,share-nightFirst.size());
            evening=new ArrayList<>(nightFirst); evening.addAll(rest);
        }
        final var eveningPool=evening;
        var daytimeOrder=ordered.stream().filter(p->!eveningPool.contains(p)).toList();
        var selected=TripConstraints.select(daytimeOrder,days,start,end,visitMinutes,constraints);
        // 예비 관광지는 저녁 몫을 뺀 후보에서 고른다. 저녁에 들른 곳이 낮 관광지로 다시 쓰이지 않게 한다.
        return new Arranged(selected,reserve(daytimeOrder.stream().filter(p->!skips.contains(p.placeId())).toList(),selected),evening);
    }

    /**
     * '다른 관광지로 다시 만들기'면 직전 초안의 관광지(previous)를 저녁 관광 후보에서 뺀다. 예비 관광지에는 남겨 두되(후보 순서 맨 뒤라 뒤쪽에 온다)
     * 일정 생성이 다른 예비 관광지가 없을 때만 쓴다(2026-10-31). 예전에는 예비에서도 빼서, 직전에 간 곳이 가깝다는 이유로 다시 들어가지는
     * 않았지만(2026-10-30 브라우저 확인) 채울 곳이 없는 날은 관광지가 줄고 시간이 비었다(여수 2일차 1곳, 300분).
     */
    static Arranged withoutPreviousEvening(Arranged arranged,Collection<Long> previous) {
        if(previous.isEmpty()) return arranged;
        return new Arranged(arranged.selected(),arranged.reserve(),
            arranged.evening().stream().filter(p->!previous.contains(p.placeId())).toList());
    }

    /** 예비 관광지 수. 섬처럼 갈 수 없어 빠진 곳을 채울 만큼만 둔다. */
    static final int MAX_RESERVE=6;
    /** 고르지 않은 후보 중 앞에서부터(가운데 가까운 곳·종류를 섞은 순서) 예비 관광지로 둔다. */
    static List<PlaceCandidate> reserve(List<PlaceCandidate> ordered,List<PlaceCandidate> selected) {
        var ids=selected.stream().map(PlaceCandidate::placeId).toList();
        return ordered.stream().filter(p->!ids.contains(p.placeId())).limit(MAX_RESERVE).toList();
    }

    /**
     * 필수·중심 장소 중 관광지 후보에 없는 곳을 지금 확인된 이 지역 관광지인지 다시 읽어 후보에 더한다.
     * 후보 수 제한·순서 변경·24시간 경과로 목록에서 밀려났을 뿐이면 그대로 쓰고, 확인하지 못하면 이름을 적어 ConflictException을 던진다.
     */
    private PlaceCandidateView withChosenPlaces(PlaceCandidateView pool,Constraints constraints) {
        if(constraints==null) return pool;
        var chosen=new LinkedHashSet<Long>(constraints.requiredPlaceIds()); chosen.addAll(constraints.centerPlaceIds());
        var attractions=new ArrayList<>(pool.attractions());
        var missing=new ArrayList<String>();
        for(Long id:chosen) {
            if(id==null || findPlace(attractions,id)!=null) continue;
            var now=candidates.usableAttraction(id,pool.destination().region());
            if(now.isPresent()) attractions.add(now.get());
            else missing.add(places.findById(id).map(Place::getName).orElse("고른 관광지"));
        }
        if(!missing.isEmpty())
            throw new TripConstraints.ConflictException(List.of(new TripConstraints.Conflict(
                "'"+String.join("', '",missing)+"'의 장소 정보를 지금 다시 확인하지 못했어요.",
                List.of("꼭 가야 하는 관광지·일정의 중심에서 이 장소를 빼고 다시 만들기",
                    "장소 목록에서 '카카오에서 장소 더 찾아보기'로 장소 정보를 새로 확인한 뒤 다시 만들기"))));
        if(attractions.size()==pool.attractions().size()) return pool;
        return new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),pool.accommodationNeeded(),
            List.copyOf(attractions),pool.accommodations(),pool.restaurants());
    }

    private TripConstraints.ConflictException scheduleConflict(String message) {
        return new TripConstraints.ConflictException(List.of(new TripConstraints.Conflict(message,
            List.of("아래 여행 기간·숙박 여부·출발일을 고르거나 추천 화면의 조건 수정에서 변경해 주세요."))));
    }

    /** 교체 사유. 실내 변경만 후보 선택 순서에 반영하고 나머지는 기록·안내에만 사용한다. */
    public enum ReplaceReason {
        TASTE("취향과 맞지 않아요"), INDOOR("실내 장소로 바꾸고 싶어요"), VISITED("이미 가 본 곳이에요"), OTHER("기타"),
        AI_REQUEST("문장으로 요청"), DIRECT("직접 고쳐서 수정");
        private final String label;
        ReplaceReason(String label) { this.label=label; }
        public String getLabel() { return label; }
    }
    private static final java.util.regex.Pattern INDOOR=java.util.regex.Pattern.compile("박물관|미술관|전시|과학관|아쿠아리움|기념관|체험관|실내|갤러리|아트");

    /**
     * 완성된 초안에서 유지할 관광지는 그대로 두고 교체할 관광지만 확인된 다른 후보로 바꾼 수정안 작업을 만든다.
     * 원본 초안은 바꾸지 않으며, 수정안도 새 작업으로 같은 배치·검증 과정을 거친다.
     */
    @Transactional(timeout=10)
    public UUID revise(UUID sourceId,String login,byte[] hash,List<Long> replace,ReplaceReason reason,
                       LocalTime start,LocalTime end,int visitMinutes) {
        if(reason==ReplaceReason.AI_REQUEST || reason==ReplaceReason.DIRECT) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"교체 이유를 다시 골라 주세요.");
        // 'AI 없이 시간만 바꾸기'에서 지금과 같은 시간을 다시 보내면 같은 일정만 하나 더 생기므로 막는다(직접 수정과 같은 검사).
        // 아직 완성되지 않은 초안은 아래 공통 검사에서 '완성된 일정 초안에서만'으로 안내한다.
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if((replace==null || replace.isEmpty()) && "COMPLETED".equals(source.getStatus())) {
            var original=mapper.readValue(source.getRequestJson(),Input.class);
            if(Objects.equals(start,original.start()) && Objects.equals(end,original.end()) && visitMinutes==original.visitMinutes())
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"바꾼 내용이 없어요. 바꿀 시간을 고른 뒤 다시 눌러 주세요.");
        }
        return revise(sourceId,login,hash,replace,null,reason,start,end,visitMinutes,null,null,List.of(),Changes.NONE);
    }

    /**
     * 수정 요청으로 관광지·시간 말고 함께 바꾸는 것. null이면 원본 초안의 값을 그대로 쓴다.
     * transport는 이동수단, choices는 식당·숙소 선택, home은 집 출발·귀가(이동수단이 바뀌면 편도 시간도 다시 찾은 값)다.
     */
    /** remove는 다른 곳으로 바꾸지 않고 일정에서 빼기만 하는 관광지다(직접 수정 화면의 '빼기'). */
    public record Changes(KakaoMapClient.Transport transport,Choices choices,HomeTrip home,List<Long> remove) {
        static final Changes NONE=new Changes(null,null,null);
        Changes(KakaoMapClient.Transport transport,Choices choices,HomeTrip home) { this(transport,choices,home,List.of()); }
    }

    /**
     * add가 있으면 그 후보로 순서대로 바꾸고(replace보다 많으면 남는 곳은 더한다), 없으면 기존 규칙(후보 순서·실내 우선)으로 고른다.
     * add에는 확인된 후보 목록 밖이라도 이번 요청으로 카카오에서 찾아 저장한 이 지역 관광지가 올 수 있다.
     */
    private UUID revise(UUID sourceId,String login,byte[] hash,List<Long> replace,List<Long> add,ReplaceReason reason,
                        LocalTime start,LocalTime end,int visitMinutes,String request,String explanation,List<String> unavailable,Changes changes) {
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(source.getStatus()) || source.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 수정안을 만들 수 있어요.");
        var original=mapper.readValue(source.getRequestJson(),Input.class);
        var originalResult=mapper.readValue(source.getResultJson(),Result.class);
        var placed=placed(original,originalResult);
        var chosen=new LinkedHashSet<>(replace==null?List.<Long>of():replace);
        // 빼기만 하는 관광지도 원본에서 빠지는 곳이라 함께 센다. 새 후보로 채울 수(need)에는 넣지 않는다.
        chosen.addAll(changes.remove());
        int need=chosen.size()-changes.remove().size();
        var originalIds=placed.stream().map(PlaceCandidate::placeId).toList();
        if(!originalIds.containsAll(chosen))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 초안에 있는 관광지만 교체할 수 있어요.");
        if(chosen.size()==originalIds.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"유지할 관광지를 한 곳 이상 남겨 주세요. 모두 바꾸려면 일정을 새로 만들어 주세요.");
        if(need>0 && reason==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"교체 이유를 골라 주세요.");
        // 필수 방문 장소는 이 일정의 필수 조건이라 교체 대상이 아니다.
        if(original.constraints()!=null && chosen.stream().anyMatch(original.constraints().requiredPlaceIds()::contains))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"필수 방문 장소로 지정한 관광지는 바꿀 수 없어요. 새 일정에서 필수 조건을 바꿔 주세요.");
        long destinationId=original.places().destination().placeId();
        // 현재 확인된 후보 전체를 다시 읽는다. 오래된 초안의 장소 정보로 수정안을 만들지 않는다.
        // 조건은 원본 초안의 스냅샷(일정 생성 때 받은 기간·숙박 포함)을 쓴다. 부모 추천에는 기간이 비어 있을 수 있다.
        var pool=candidates.findForSchedule(original.recommendationId(),destinationId,login,hash,original.conditions());
        var kept=new ArrayList<PlaceCandidate>();
        for(var place:placed) {
            if(chosen.contains(place.placeId())) continue;
            // 후보 수 제한·순서 변경으로 목록에서 밀려났을 뿐 지금도 확인된 장소면 그대로 유지한다.
            kept.add(pool.attractions().stream().filter(p->p.placeId().equals(place.placeId())).findFirst()
                .or(()->candidates.usableAttraction(place.placeId(),pool.destination().region())).orElseThrow(()->
                new ResponseStatusException(HttpStatus.CONFLICT,"관광지 '"+place.name()+"'의 장소 정보를 지금 다시 확인하지 못했어요. "
                    +"'일정 직접 수정하기'에서 이 관광지를 바꾸거나 빼고 다시 시도해 주세요.")));
        }
        // 새로 넣을 후보에서는 원본 초안이 쓰려던 관광지를 모두 뺀다. 시간·거리 때문에 빠졌던 곳(섬 등)을 다시 넣지 않는다.
        var tried=tried(original,originalResult);
        // 새 후보도 후보들 가운데에서 가까운 곳부터 고른다(이름순이면 먼 섬이 먼저 뽑힐 수 있다).
        var spare=TripConstraints.nearCenterFirst(pool.attractions()).stream().filter(p->!tried.contains(p.placeId())).toList();
        // 유지하는 관광지와 종류가 겹치지 않는 곳을 먼저 쓴다(예: 해수욕장이 이미 있으면 다른 종류부터).
        var keptKinds=kept.stream().map(TripConstraints::kind).toList();
        spare=spare.stream().sorted(Comparator.comparingInt(p->keptKinds.contains(TripConstraints.kind(p))?1:0)).toList();
        if(reason==ReplaceReason.INDOOR)
            spare=spare.stream().sorted(Comparator.comparingInt(p->indoor(p)?0:1)).toList();
        if(add!=null) {
            // AI가 고른 후보는 현재 확인된 후보 중 원본에 없던 곳이어야 한다. 후보 목록(개수 제한) 밖이면 이 지역의 확인된 관광지인지 다시 본다.
            var byId=new LinkedHashMap<Long,PlaceCandidate>(); spare.forEach(p->byId.put(p.placeId(),p));
            for(Long id:add) if(!byId.containsKey(id) && !tried.contains(id)) extraCandidate(id,pool).ifPresent(p->byId.put(p.placeId(),p));
            if(add.size()<need || !byId.keySet().containsAll(add) || new HashSet<>(add).size()!=add.size())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"수정안의 후보가 바뀌었어요. 다시 요청해 주세요.");
            var picked=add.stream().map(byId::get).toList();
            spare=new ArrayList<>(picked); byId.values().stream().filter(p->!picked.contains(p)).forEach(spare::add);
        }
        if(spare.size()<need)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"바꿀 수 있는 다른 관광지 후보가 부족해요. 장소 목록에서 후보를 더 찾아본 뒤 다시 시도해 주세요.");
        // 바꾸는 수만큼, 더하는 요청이면 그만큼 더 넣는다.
        var added=spare.subList(0,add==null?need:add.size());
        // 원본이 남는 시간에 더 넣은 관광지는 '유지해야 하는 곳'으로 묶지 않고 예비 관광지 맨 앞에 둔다.
        // 하루 시간이 남으면 다시 들어가고, 시간을 줄인 수정안에서는 빠진다.
        // 관광지를 빼기만 하는 수정안은 나머지 일정을 날짜·순서 그대로 두므로, 남는 시간에 더 넣은 곳까지 모두 유지한다(2026-11-02).
        boolean removeOnly=!changes.remove().isEmpty() && need==0 && add==null && start.equals(original.start()) && end.equals(original.end())
            && visitMinutes==original.visitMinutes() && (changes.transport()==null || changes.transport()==original.transport())
            && (changes.choices()==null || changes.choices().equals(original.choices()));
        var keptDays=removeOnly?keptDays(originalResult,placed,chosen):null;
        var firm=firmPlaceIds(original);
        var keptFirm=kept.stream().filter(p->firm.contains(p.placeId())).toList();
        if(keptFirm.isEmpty() || keptDays!=null) keptFirm=kept;
        final var keptIds=keptFirm.stream().map(PlaceCandidate::placeId).toList();
        var extras=kept.stream().filter(p->!keptIds.contains(p.placeId())).toList();
        var attractions=new ArrayList<>(keptFirm); attractions.addAll(added);
        // 식당·숙소는 지금 확인된 후보를 쓴다. 이번 요청에서 고른 식당·숙소가 후보 목록(개수 제한) 밖이면(주변 식당 등)
        // 지금도 확인된 장소인지 다시 읽어 넣는다. 원본 초안의 옛 정보를 그대로 넣으면 같은 장소가 두 번 들어가 완료 검사에서 실패하거나
        // 24시간이 지나 '장소 정보가 오래됐어요'로 실패했다. 그래서 장소 ID로 비교하고, 확인하지 못한 곳은 빼고 안내한다.
        var choices=changes.choices()==null?original.choices():changes.choices();
        // 숙소를 따로 고르지 않은 수정안(시간만 바꾸기, 관광지 바꾸기, 숙소를 말하지 않은 문장 수정 등)은 지금 초안의 숙소를 그대로 쓴다.
        // 처음 만든 초안은 '고른 숙소' 값이 비어 있어, 그대로 두면 시간만 바꿔도 숙소가 말없이 바뀌었다.
        if(choices.stayPlaceId()==null && originalResult.lodging()!=null && originalResult.lodging().chosenPlaceId()!=null)
            choices=new Choices(choices.avoidRestaurants(),choices.preferRestaurants(),originalResult.lodging().chosenPlaceId());
        String region=pool.destination().region();
        var choiceNotices=new ArrayList<String>();
        var restaurants=new ArrayList<>(pool.restaurants());
        // 그대로 둘 식당이 후보 목록(개수 제한) 밖이면 지금도 확인된 장소인지 다시 읽어 넣는다. 확인하지 못한 식당만 다른 곳으로 고른다.
        if(keptDays!=null) for(var day:keptDays) for(Long id:day.meals())
            if(findPlace(restaurants,id)==null) candidates.usableRestaurant(id,region).ifPresent(restaurants::add);
        var prefer=new ArrayList<Long>();
        for(Long id:choices.preferRestaurants()) {
            var before=findPlace(original.places().restaurants(),id);
            if(findPlace(restaurants,id)!=null || before==null) { prefer.add(id); continue; }
            var now=candidates.usableRestaurant(id,region);
            if(now.isPresent()) { restaurants.add(now.get()); prefer.add(id); }
            else choiceNotices.add("고른 식당 '"+before.name()+"'의 정보를 지금 확인하지 못해 다른 곳으로 골랐어요.");
        }
        var stays=new ArrayList<>(pool.accommodations());
        Long stay=choices.stayPlaceId();
        if(stay!=null && pool.accommodationNeeded() && findPlace(stays,stay)==null) {
            var now=candidates.usableAccommodation(stay,region);
            if(now.isPresent()) stays.add(now.get());
            else {
                var before=findPlace(original.places().accommodations(),stay);
                choiceNotices.add("고른 숙소"+(before==null?"":" '"+before.name()+"'")+"의 정보를 지금 확인하지 못해 다른 곳으로 골랐어요.");
                stay=null;
            }
        }
        choices=new Choices(choices.avoidRestaurants(),prefer,stay);
        var places=new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),
            pool.accommodationNeeded(),List.copyOf(attractions),List.copyOf(stays),List.copyOf(restaurants));
        var revision=new Revision(sourceId,refs(keptFirm),refs(placed.stream().filter(p->chosen.contains(p.placeId())).toList()),
            refs(added),chosen.isEmpty() && reason!=ReplaceReason.AI_REQUEST?null:reason.name(),Instant.now(),request,explanation,
            List.copyOf(unavailable),List.copyOf(choiceNotices),keptDays);
        // 새로 넣지 않고 남은 후보는 이 수정안의 예비 관광지로 둔다.
        var spareLeft=new ArrayList<>(extras);
        // 관광지를 뺀 수정안은 빈 시간을 다른 예비 관광지로 다시 채우지 않는다(뺀 자리는 자유 시간으로 남는다).
        if(changes.remove().isEmpty())
            spare.subList(added.size(),spare.size()).stream().limit(MAX_RESERVE).forEach(spareLeft::add);
        // 저녁 관광 후보와 집 출발·귀가 설정은 원본 초안에서 이어받는다. 이번에 관광지로 넣은 곳은 저녁 관광 후보에서 뺀다.
        // 저녁 관광 후보도 지금 확인된 정보로 바꾸고, 다시 확인하지 못한 곳은 뺀다(식당·숙소와 같은 이유).
        Set<Long> used=new HashSet<>();
        attractions.forEach(p->used.add(p.placeId())); spareLeft.forEach(p->used.add(p.placeId()));
        var evening=new ArrayList<PlaceCandidate>();
        for(var p:original.evening()) {
            if(used.contains(p.placeId())) continue;
            var now=findPlace(pool.attractions(),p.placeId());
            if(now!=null) evening.add(now); else candidates.usableAttraction(p.placeId(),region).ifPresent(evening::add);
        }
        var input=new Input(original.recommendationId(),places,original.conditions(),start,end,
            changes.transport()==null?original.transport():changes.transport(),visitMinutes,revision,
            original.constraints(),List.copyOf(spareLeft),evening,changes.home()==null?original.home():changes.home(),choices)
            // 수정안도 원본 초안이 AI 순서로 고른 것인지 이어받는다(방문 이유 문구에 쓴다).
            .withPicking(original.pickedByAi()?new Picking(List.of(),true):null);
        generator.checkInput(input);
        var parent=access.owned(original.recommendationId(),login,hash,"RECOMMENDATION");
        checkActiveJobs(parent,hash);
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }
    /**
     * AI 수정 결과. job이 있으면 새 수정안 작업, question이 있으면 일정을 바꾸지 않고 확인할 질문,
     * notice가 있으면 할 수 없는 요청이라 일정을 바꾸지 않았다는 안내다.
     */
    /** askedPlaces는 이번에 "후보에 없다"고 되물은 장소다. 같은 초안에서 다시 요청하면 되묻지 않고 비슷한 곳으로 바꾼다. */
    public record AiRevision(UUID job,String question,String notice,List<String> askedPlaces) {
        AiRevision(UUID job,String question,String notice) { this(job,question,notice,List.of()); }
        AiRevision(UUID job,String question) { this(job,question,null); }
    }

    /**
     * 자연어 수정 요청(SFR-08-04)을 GPT로 수정안 형식으로 바꾼 뒤 기존 수정안 흐름으로 새 초안을 만든다.
     * 형식이 틀린 응답은 사유를 알려 최대 2회 다시 요청하고, 모호하면 질문만 돌려준다. 원본 초안은 바꾸지 않는다.
     * 외부 호출 동안 트랜잭션을 잡지 않도록 이 메서드 자체에는 트랜잭션을 두지 않는다.
     */
    public AiRevision aiRevise(UUID sourceId,String login,byte[] hash,String request) {
        return aiRevise(sourceId,login,hash,request,Set.of());
    }

    /** askedBefore는 이 초안에서 이미 "후보에 없다"고 되물은 장소 이름이다(세션에 보관). */
    public AiRevision aiRevise(UUID sourceId,String login,byte[] hash,String request,Set<String> askedBefore) {
        String text=request==null?"":request.strip();
        if(text.isEmpty() || text.length()>300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수정 요청을 1~300자로 적어 주세요.");
        var context=self().aiContext(sourceId,login,hash);
        var proposal=propose(context,text);
        if(proposal.unsupported()) return new AiRevision(null,null,proposal.question());
        // 사용자가 콕 집은 관광지가 후보에 없으면 카카오에서 이 지역 관광지로 찾아 보고, 찾으면 후보에 넣어 한 번 더 요청한다.
        var discovery=discoveries.getIfAvailable();
        if(proposal.exactPlace() && !proposal.unavailable().isEmpty() && discovery!=null) {
            var found=new ArrayList<OpenAiRevisionClient.Candidate>();
            var destination=context.input().places().destination();
            for(String name:proposal.unavailable())
                discovery.findAttraction(destination.placeId(),destination.region(),name).map(OpenAiRevisionClient::candidate).ifPresent(found::add);
            if(!found.isEmpty()) {
                found.addAll(context.spare());
                context=new AiContext(context.input(),context.result(),context.current(),List.copyOf(found),context.fixed());
                proposal=propose(context,text);
                if(proposal.unsupported()) return new AiRevision(null,null,proposal.question());
            }
        }
        if(proposal.clarify()) return new AiRevision(null,proposal.question());
        // 그래도 후보에 없으면 처음에는 임의로 바꾸지 않고 되묻는다. 같은 장소를 다시 요청하면 비슷한 곳으로 바꾼다.
        var missing=proposal.unavailable();
        if(proposal.exactPlace() && !missing.isEmpty() && !askedBefore.containsAll(missing))
            return new AiRevision(null,unavailableQuestion(missing),null,missing);
        var original=context.input();
        LocalTime start=proposal.start()==null?original.start():proposal.start();
        LocalTime end=proposal.end()==null?original.end():proposal.end();
        int visit=proposal.visitMinutes()==null?original.visitMinutes():proposal.visitMinutes();
        var transport=proposal.transport()==null?original.transport():proposal.transport();
        // 첫날 집 출발·마지막 날 귀가 시각은 집 출발·귀가를 정한 일정에서만 바꾼다.
        var home=original.home();
        // "첫날 11시에 도착"처럼 도착 시각을 말했으면 편도 시간과 여유 10분을 빼서 집 출발 시각을 서버가 계산한다(10분 단위로 내림).
        LocalTime departAt=proposal.departAt();
        if(home!=null && departAt==null && proposal.firstArrival()!=null) {
            int minutes=proposal.firstArrival().toSecondOfDay()/60-home.minutes()-HomeTrip.SPARE_MINUTES;
            if(minutes<0) return new AiRevision(null,proposal.firstArrival()+"까지 도착하려면 편도 약 "+home.minutes()+"분이 걸려 전날 출발해야 해요. 도착 시각을 늦춰 주세요.");
            departAt=LocalTime.ofSecondOfDay(minutes/10*10*60L);
        }
        if(home!=null && (departAt!=null || proposal.arriveBy()!=null))
            home=new HomeTrip(home.origin(),departAt==null?home.departAt():departAt,
                proposal.arriveBy()==null?home.arriveBy():proposal.arriveBy(),home.minutes(),home.basis(),home.checkedAt(),
                home.returnPlace(),home.returnMinutes(),home.returnBasis());
        // 요청 문장에 편도 시간을 적었으면(예: 여행지까지 편도 4시간) 그 값을 쓰고, 이동수단만 바뀌면 새 이동수단으로 다시 찾는다.
        // GPT가 지금 값을 그대로 다시 적은 경우는 바꾸지 않은 것으로 본다.
        Integer typedOut=home!=null && proposal.homeMinutes()!=null && proposal.homeMinutes()!=home.minutes()?proposal.homeMinutes():null;
        Integer typedBack=home!=null && proposal.returnMinutes()!=null && !proposal.returnMinutes().equals(home.returnMinutes())?proposal.returnMinutes():null;
        if(home!=null && (transport!=original.transport() || typedOut!=null || typedBack!=null)) {
            try { home=homeFor(home,original.places(),transport,typedOut,typedBack); }
            catch(RecommendationException error) {
                if(!error.getCode().startsWith("HOME_") && !error.getCode().startsWith("MAP_")) throw error;
                return new AiRevision(null,"이동수단을 바꾸면 집 ↔ 여행지 이동시간도 다시 찾아야 하는데 찾지 못했어요. "
                    +"편도 시간을 함께 적어 다시 요청해 주세요. 예) 대중교통으로 다닐게요. 여행지까지 편도 4시간 걸려요.");
            }
        }
        // 식당·숙소 선택은 원본 초안의 선택에 이번 요청을 더한다. 빼 달라고 한 식당과 가고 싶다는 식당이 겹치면 이번 요청을 따른다.
        var before=original.choices();
        var avoid=new LinkedHashSet<>(before.avoidRestaurants()); avoid.addAll(proposal.avoidRestaurants()); proposal.preferRestaurants().forEach(avoid::remove);
        var prefer=new LinkedHashSet<>(before.preferRestaurants()); prefer.addAll(proposal.preferRestaurants()); proposal.avoidRestaurants().forEach(prefer::remove);
        var choices=new Choices(List.copyOf(avoid),List.copyOf(prefer),proposal.stayPlaceId()==null?before.stayPlaceId():proposal.stayPlaceId());
        if(proposal.replace().isEmpty() && proposal.add().isEmpty() && start.equals(original.start()) && end.equals(original.end())
                && visit==original.visitMinutes() && transport==original.transport() && Objects.equals(home,original.home()) && choices.equals(before))
            return new AiRevision(null,"바꿀 내용을 찾지 못했어요. 어떤 관광지나 시간을 바꾸고 싶은지 조금 더 구체적으로 적어 주세요.");
        return new AiRevision(self().reviseAi(sourceId,login,hash,proposal.replace(),proposal.add(),start,end,visit,text,
            proposal.explanation(),missing,new Changes(transport,choices,home)),null);
    }

    /** GPT에 수정안을 요청한다. 형식이 틀리면 사유를 알려 최대 2회 다시 요청한다. */
    private OpenAiRevisionClient.Proposal propose(AiContext context,String text) {
        String failure=null;
        for(int attempt=0;;attempt++) {
            try {
                return revisions.propose(context.input().conditions(),context.input(),context.result(),context.current(),
                    context.spare(),context.fixed(),text,failure);
            } catch(RecommendationException e) {
                if(!"AI_INVALID_RESPONSE".equals(e.getCode())) throw e;
                // 세 번 모두 형식이 틀리면 내부 사유(필드 이름) 대신 사용자가 고칠 수 있는 안내를 보여 준다.
                if(attempt==2) throw new RecommendationException("AI_INVALID_RESPONSE",
                    "AI가 이 요청을 수정안으로 바꾸지 못했어요. 바꿀 관광지나 시각을 한 가지씩 짧게 적어 주세요.");
                failure=e.getMessage();
            }
        }
    }

    /**
     * 집 ↔ 여행지 편도 시간을 새 이동수단·요청한 시간으로 다시 정한다. 요청 문장에 적은 시간 → 전에 직접 적은 시간 → 새로 찾기 순서다.
     * 출발지·돌아갈 곳은 저장해 둔 시·구 이름으로 다시 위치를 찾는다. 가는 길만 적으면 돌아올 곳이 같을 때 오는 길도 같게 본다.
     */
    private HomeTrip homeFor(HomeTrip home,PlaceCandidateView places,KakaoMapClient.Transport transport,Integer typedOut,Integer typedBack) {
        String manual="직접 입력한 편도 시간";
        boolean sameReturn=home.returnPlace().equals(home.origin());
        Integer out=typedOut!=null?typedOut:manual.equals(home.basis())?Integer.valueOf(home.minutes()):null;
        Integer back=typedBack!=null?typedBack:sameReturn && typedOut!=null?typedOut:manual.equals(home.returnBasis())?home.returnMinutes():null;
        // 두 방향 모두 시간을 알면 위치를 다시 찾지 않는다.
        if(out!=null && back!=null)
            return new HomeTrip(home.origin(),home.departAt(),home.arriveBy(),out,manual,null,home.returnPlace(),back,manual);
        var service=homeTrips.getIfAvailable();
        if(service==null) return home;
        return service.resolve(new HomeTripService.Request(home.origin(),null,null,home.departAt(),home.arriveBy(),out,
            sameReturn?null:home.returnPlace(),back),places,transport);
    }

    /** 콕 집은 장소가 후보에 없을 때 되묻는 서버 고정 문구. GPT 문장 대신 써서 할 수 있는 일을 정확히 알린다. */
    static String unavailableQuestion(List<String> names) {
        return "'"+String.join("', '",names)+"'은(는) 이 지역에서 확인된 관광지로 찾지 못했어요. 장소 이름을 다시 확인해 적거나, "
            +"같은 요청을 한 번 더 보내면 비슷한 곳으로 바꾼 수정안을 만들어 드려요.";
    }
    @Transactional(timeout=10)
    public UUID reviseAi(UUID sourceId,String login,byte[] hash,List<Long> replace,List<Long> add,
                         LocalTime start,LocalTime end,int visitMinutes,String request,String explanation,List<String> unavailable,Changes changes) {
        return revise(sourceId,login,hash,replace,add,ReplaceReason.AI_REQUEST,start,end,visitMinutes,request,explanation,unavailable,changes);
    }
    /**
     * 직접 수정 화면에 보여 줄 것. addable은 새로 더할 수 있는 관광지 후보(가까운 순, 최대 12곳),
     * editableIds는 바꾸거나 뺄 수 있는 관광지(일정에 들어간 곳 중 필수 방문 장소가 아닌 곳)다.
     */
    public record EditOptions(Input input,Result result,List<PlaceCandidate> addable,Set<Long> editableIds) {}

    @Transactional(readOnly=true,timeout=10)
    public EditOptions editOptions(UUID sourceId,String login,byte[] hash) {
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(source.getStatus()) || source.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 수정할 수 있어요.");
        var input=mapper.readValue(source.getRequestJson(),Input.class);
        var result=mapper.readValue(source.getResultJson(),Result.class);
        var pool=candidates.findForSchedule(input.recommendationId(),input.places().destination().placeId(),login,hash,input.conditions());
        // 원본 초안이 쓰려던 곳(넣은 곳·뺀 곳)은 새로 더할 후보에서 뺀다. 수정안을 만들 때의 검사(revise)와 같은 기준이다.
        var tried=tried(input,result);
        var addable=TripConstraints.nearCenterFirst(pool.attractions()).stream().filter(p->!tried.contains(p.placeId())).limit(12).toList();
        var editable=new LinkedHashSet<Long>();
        placed(input,result).forEach(p->editable.add(p.placeId()));
        if(input.constraints()!=null) editable.removeAll(input.constraints().requiredPlaceIds());
        return new EditOptions(input,result,addable,editable);
    }

    /**
     * 직접 수정 화면에서 고른 변경. 고르지 않은 값은 null(목록은 비어 있음)이고 원본 초안의 값을 그대로 쓴다.
     * remove는 뺄 관광지, add는 더할 관광지, avoidRestaurantId는 다른 곳으로 바꿀 식당, stayPlaceId는 바꿀 숙소다.
     */
    public record DirectChange(List<Long> remove,List<Long> add,Long avoidRestaurantId,Long stayPlaceId,
                               KakaoMapClient.Transport transport,LocalTime start,LocalTime end,Integer visitMinutes) {
        public DirectChange {
            remove=remove==null?List.of():List.copyOf(remove);
            add=add==null?List.of():List.copyOf(add);
        }
    }

    /**
     * 사용자가 직접 고른 변경으로 수정안 작업을 만든다(SFR-08-04). AI를 쓰지 않고, 시각 배치와 검증은 AI 수정과 같은 과정을 거친다.
     * 이동수단을 바꾸면 집 ↔ 여행지 이동시간을 다시 찾느라 외부 호출이 있어 이 메서드에는 트랜잭션을 두지 않는다.
     */
    public UUID reviseDirect(UUID sourceId,String login,byte[] hash,DirectChange change) {
        var options=self().editOptions(sourceId,login,hash);
        var original=options.input();
        LocalTime start=change.start()==null?original.start():change.start();
        LocalTime end=change.end()==null?original.end():change.end();
        int visit=change.visitMinutes()==null?original.visitMinutes():change.visitMinutes();
        var transport=change.transport()==null?original.transport():change.transport();
        if(!options.editableIds().containsAll(change.remove()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 일정에 있는 관광지만 뺄 수 있어요. 필수 방문 장소는 뺄 수 없어요.");
        // 식당·숙소는 이 초안에 실제로 나온 곳만 고를 수 있다.
        var before=original.choices();
        var avoid=new LinkedHashSet<>(before.avoidRestaurants());
        var prefer=new LinkedHashSet<>(before.preferRestaurants());
        if(change.avoidRestaurantId()!=null) {
            boolean inPlan=options.result().days().stream().flatMap(d->d.items().stream())
                .anyMatch(i->"MEAL".equals(i.type()) && change.avoidRestaurantId().equals(i.placeId()));
            if(!inPlan) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 일정에 있는 식당만 바꿀 수 있어요.");
            avoid.add(change.avoidRestaurantId()); prefer.remove(change.avoidRestaurantId());
        }
        Long stay=before.stayPlaceId();
        if(change.stayPlaceId()!=null) {
            var lodging=options.result().lodging();
            if(lodging==null || lodging.options().stream().noneMatch(o->o.placeId().equals(change.stayPlaceId())))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 일정의 숙소 후보 중에서 골라 주세요.");
            stay=change.stayPlaceId();
        }
        var choices=new Choices(List.copyOf(avoid),List.copyOf(prefer),stay);
        // 이동수단이 바뀌면 집 ↔ 여행지 편도 시간도 새 이동수단으로 다시 찾는다.
        var home=original.home();
        if(home!=null && transport!=original.transport()) {
            try { home=homeFor(home,original.places(),transport,null,null); }
            catch(RecommendationException error) {
                if(!error.getCode().startsWith("HOME_") && !error.getCode().startsWith("MAP_")) throw error;
                throw new ResponseStatusException(HttpStatus.CONFLICT,"이동수단을 바꾸면 집 ↔ 여행지 이동시간도 다시 찾아야 하는데 찾지 못했어요. 잠시 후 다시 시도해 주세요.");
            }
        }
        if(change.remove().isEmpty() && change.add().isEmpty() && start.equals(original.start()) && end.equals(original.end())
                && visit==original.visitMinutes() && transport==original.transport() && choices.equals(before))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"바꾼 내용이 없어요. 바꿀 것을 고른 뒤 다시 눌러 주세요.");
        return self().reviseChosen(sourceId,login,hash,change.add().isEmpty()?null:change.add(),start,end,visit,
            new Changes(transport,choices,home,change.remove()));
    }
    @Transactional(timeout=10)
    public UUID reviseChosen(UUID sourceId,String login,byte[] hash,List<Long> add,LocalTime start,LocalTime end,int visitMinutes,Changes changes) {
        return revise(sourceId,login,hash,List.of(),add,ReplaceReason.DIRECT,start,end,visitMinutes,null,null,List.of(),changes);
    }
    public record AiContext(Input input,Result result,List<OpenAiRevisionClient.Candidate> current,
                            List<OpenAiRevisionClient.Candidate> spare,Set<Long> fixed) {}
    /** GPT에 보낼 현재 일정·후보를 읽는다. 외부 호출 전에 끝나는 짧은 읽기 트랜잭션이다. */
    @Transactional(readOnly=true,timeout=10)
    public AiContext aiContext(UUID sourceId,String login,byte[] hash) {
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(source.getStatus()) || source.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 수정을 요청할 수 있어요.");
        if(!revisions.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 수정 요청은 OpenAI 연결 설정 후 이용할 수 있어요. 일정의 '다른 곳으로 바꾸기'와 'AI 없이 시간만 바꾸기'는 그대로 쓸 수 있어요.");
        var input=mapper.readValue(source.getRequestJson(),Input.class);
        var result=mapper.readValue(source.getResultJson(),Result.class);
        var pool=candidates.findForSchedule(input.recommendationId(),input.places().destination().placeId(),login,hash,input.conditions());
        var placed=placed(input,result);
        var currentIds=placed.stream().map(PlaceCandidate::placeId).toList();
        var current=placed.stream().map(OpenAiRevisionClient::candidate).toList();
        // 원본 초안이 쓰려다 뺀 곳(skipped)도 AI가 새로 고를 후보에서 뺀다.
        var tried=tried(input,result);
        var spare=TripConstraints.nearCenterFirst(pool.attractions()).stream().filter(p->!tried.contains(p.placeId())).map(OpenAiRevisionClient::candidate).toList();
        Set<Long> fixed=input.constraints()==null?Set.of():Set.copyOf(input.constraints().requiredPlaceIds());
        return new AiContext(input,result,current,spare,fixed);
    }
    private ItineraryJobService self() { return selfProvider.getObject(); }

    /** 한 번에 가격을 찾는 장소 수. 숙소 1곳과 일정에 넣은 음식점을 앞에서부터 센다. 유료 검색이라 상한을 둔다. */
    static final int MAX_PRICE_PLACES=8;
    /** 같은 초안을 다시 눌러도 이 시간 안에는 이전 조회 결과를 쓴다. 결과 화면은 이 시간이 지난 뒤에 '다시 찾기'를 보여 준다. */
    public static final Duration PRICE_REUSE=Duration.ofHours(24);
    /** 요청 하나가 새 검색을 시작할 수 있는 시간. 리버스 프록시의 읽기 제한(보통 60초)에 걸리지 않도록 보통은 이 안에 끝내고, 남은 곳은 다시 눌러 이어서 찾는다. */
    static final Duration PRICE_TIME_LIMIT=Duration.ofSeconds(45);
    /** stored는 이 장소에 저장해 둔 재사용 가능한 가격이다. 있으면 다시 검색하지 않는다. */
    public record PriceTarget(PlaceCandidate place,String kind,PriceQuote stored) {
        PriceTarget(PlaceCandidate place,String kind) { this(place,kind,null); }
    }
    public record SearchedPrice(PlaceCandidate place,PriceQuote quote) {}
    public record PriceContext(String resultJson,List<PriceTarget> targets,Prices cached) {}

    /**
     * 완성된 초안의 숙소·음식점 참고 가격을 AI 웹 검색으로 찾아 결과에 붙인다.
     * 외부 호출 동안 DB 연결을 잡지 않도록 읽기 → 검색 → 저장을 나누고, 이 메서드 자체에는 트랜잭션을 두지 않는다.
     */
    public Prices lookupPrices(UUID id,String login,byte[] hash) {
        var context=self().priceContext(id,login,hash);
        if(context.cached()!=null) return context.cached();
        var deadline=Instant.now().plus(PRICE_TIME_LIMIT);
        List<PriceQuote> quotes=new ArrayList<>(); boolean incomplete=false;
        // 장소마다 따로 검색해야 다른 가게 가격이 섞이지 않는다. 앞에서부터 차례로 찾고, 저장해 둔 가격이 있는 곳은 건너뛴다.
        for(var target:context.targets()) {
            if(target.stored()!=null) { quotes.add(target.stored()); continue; }
            // 시간이 다 됐거나 그 곳만 응답이 늦으면 못 찾음으로 두고 나머지 결과를 보여 준다. 다시 누르면 남은 곳만 찾는다.
            if(Instant.now().isAfter(deadline)) {
                quotes.add(OpenAiPriceClient.notFound(target.place(),target.kind()).checkedAt(Instant.now())); incomplete=true; continue;
            }
            try {
                var quote=priceClient.lookup(target.place(),target.kind()).checkedAt(Instant.now());
                quotes.add(quote);
                // 중간에 실패하거나 다른 탭에서 다시 눌러도 이미 비용을 낸 검색을 되풀이하지 않게 한 곳마다 바로 장소에 남긴다.
                try { self().storePlacePrices(List.of(new SearchedPrice(target.place(),quote))); }
                catch(RuntimeException e) { log.warn("참고 가격을 장소에 저장하지 못함 job={} ({})",id,e.toString()); }
            } catch(RecommendationException e) {
                if(!"AI_TIMEOUT".equals(e.getCode())) throw e;
                quotes.add(OpenAiPriceClient.notFound(target.place(),target.kind()).checkedAt(Instant.now())); incomplete=true;
            }
        }
        var prices=new Prices(quotes,Instant.now(),incomplete);
        self().savePrices(id,login,hash,context.resultJson(),prices);
        return prices;
    }
    @Transactional(readOnly=true,timeout=10)
    public PriceContext priceContext(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(job.getStatus()) || job.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 가격을 찾을 수 있어요.");
        var result=mapper.readValue(job.getResultJson(),Result.class);
        // 시간 안에 다 찾지 못한 묶음은 재사용하지 않아 남은 곳을 이어서 찾을 수 있게 한다.
        if(result.prices()!=null && !result.prices().incomplete() && result.prices().checkedAt().isAfter(Instant.now().minus(PRICE_REUSE)))
            return new PriceContext(job.getResultJson(),List.of(),result.prices());
        if(!priceClient.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"가격 검색은 OpenAI 연결 설정 후 이용할 수 있어요.");
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        var targets=priceTargets(input,result);
        Map<Long,Place> saved=new HashMap<>();
        places.findAllById(targets.stream().map(t->t.place().placeId()).toList()).forEach(p->saved.put(p.getId(),p));
        var now=Instant.now();
        return new PriceContext(job.getResultJson(),targets.stream().map(t->{
            var place=saved.get(t.place().placeId());
            var stored=sameAsSnapshot(place,t.place())?PlacePriceDetails.read(place,t.kind(),now,mapper):null;
            return new PriceTarget(t.place(),t.kind(),stored);
        }).toList(),null);
    }
    /** 새로 검색한 가격을 장소에 보관한다. 초안을 만든 뒤 장소 이름·주소가 바뀌었으면 보관하지 않는다. */
    @Transactional(timeout=10)
    public void storePlacePrices(List<SearchedPrice> searched) {
        for(var item:searched) places.lockById(item.place().placeId()).ifPresent(place->{
            if(!sameAsSnapshot(place,item.place())) return;
            String json=PlacePriceDetails.write(place,item.quote(),mapper);
            if(json!=null) place.updateDetails(json);
        });
        places.flush();
    }
    private static boolean sameAsSnapshot(Place place,PlaceCandidate snapshot) {
        return place!=null && place.getName().equals(snapshot.name()) && Objects.equals(place.getAddress(),snapshot.address());
    }
    /** 가격을 찾을 장소: 고른 숙소, 그다음 일정에 넣은 음식점(처음 나온 순서, 중복 없이). */
    static List<PriceTarget> priceTargets(Input input,Result result) {
        List<PriceTarget> targets=new ArrayList<>();
        if(result.lodging()!=null)
            input.places().accommodations().stream().filter(p->p.placeId().equals(result.lodging().chosenPlaceId())).findFirst()
                .ifPresent(p->targets.add(new PriceTarget(p,OpenAiPriceClient.LODGING)));
        Set<Long> seen=new HashSet<>();
        for(var day:result.days()) for(var item:day.items()) {
            if(!"MEAL".equals(item.type()) || !seen.add(item.placeId())) continue;
            input.places().restaurants().stream().filter(p->p.placeId().equals(item.placeId())).findFirst()
                .ifPresent(p->targets.add(new PriceTarget(p,OpenAiPriceClient.RESTAURANT)));
        }
        return List.copyOf(targets.subList(0,Math.min(MAX_PRICE_PLACES,targets.size())));
    }
    /** 검색하는 동안 초안이 바뀌지 않았을 때만 가격을 붙인다. */
    @Transactional(timeout=10)
    public void savePrices(UUID id,String login,byte[] hash,String expectedResult,Prices prices) {
        access.owned(id,login,hash,"ITINERARY");
        var job=jobs.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!"COMPLETED".equals(job.getStatus()) || !Objects.equals(job.getResultJson(),expectedResult))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"가격을 찾는 동안 일정이 바뀌었어요. 다시 시도해 주세요.");
        job.attachToResult(mapper.writeValueAsString(mapper.readValue(expectedResult,Result.class).withPrices(prices)));
        jobs.flush();
    }

    /** 같은 초안을 다시 눌러도 이 시간 안에는 이전 AI 확인 결과를 쓴다. */
    static final Duration VERIFICATION_REUSE=Duration.ofHours(24);
    /** stored는 이 장소에 저장해 둔 재사용 가능한 답이다. 있으면 다시 검색하지 않는다. */
    public record CheckTarget(CheckQuestion question,CheckAnswer stored) {}
    public record SearchedCheck(Long placeId,String name,CheckAnswer answer) {}
    public record CheckContext(String resultJson,List<CheckTarget> targets,Verifications cached) {}

    /**
     * 완성된 초안에서 서버가 확인하지 못한 항목(반려동물 동반·여행 목적 활동)을 AI 웹 검색으로 찾아 결과에 붙인다(버튼).
     * 참고 가격과 같이 읽기 → 검색 → 저장을 나누고, 이 메서드 자체에는 트랜잭션을 두지 않는다.
     */
    public Verifications lookupVerifications(UUID id,String login,byte[] hash) {
        var context=self().checkContext(id,login,hash);
        if(context.cached()!=null) return context.cached();
        if(context.targets().isEmpty()) throw new ResponseStatusException(HttpStatus.CONFLICT,"이 초안에는 AI로 찾아볼 확인 필요 항목이 없어요.");
        var verifications=search(context.targets(),Instant.now().plusSeconds(90));
        self().saveVerifications(id,login,hash,context.resultJson(),verifications);
        return verifications;
    }
    /** 초안을 만드는 중(자동 실행 설정)에 찾는다. 결과는 완료 시 결과 JSON에 함께 저장된다. */
    Verifications verifyNow(Input input,Result draft,Instant deadline) {
        var questions=VerificationQuestions.of(input,draft);
        if(questions.isEmpty()) return null;
        return search(self().checkTargets(questions),deadline);
    }
    /** 질문마다 따로 검색한다. 저장해 둔 답이 있는 곳은 건너뛰고, 시간이 모자라거나 그 질문만 응답이 늦으면 못 찾음으로 둔다. 비용을 낸 결과는 한 건마다 바로 장소에 남긴다. */
    private Verifications search(List<CheckTarget> targets,Instant deadline) {
        List<CheckAnswer> answers=new ArrayList<>();
        for(var target:targets) {
            if(target.stored()!=null) { answers.add(target.stored()); continue; }
            if(Instant.now().isAfter(deadline)) { answers.add(OpenAiVerificationClient.notFound(target.question())); continue; }
            try {
                var answer=verificationClient.lookup(target.question());
                answers.add(answer);
                try { self().storePlaceChecks(List.of(new SearchedCheck(target.question().placeId(),target.question().name(),answer))); }
                catch(RuntimeException e) { log.warn("AI 확인 결과를 장소에 저장하지 못함 ({})",e.toString()); }
            } catch(RecommendationException e) {
                if(!"AI_TIMEOUT".equals(e.getCode())) throw e;
                answers.add(OpenAiVerificationClient.notFound(target.question()));
            }
        }
        return new Verifications(answers,Instant.now());
    }
    @Transactional(readOnly=true,timeout=10)
    public CheckContext checkContext(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(job.getStatus()) || job.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 AI 확인 검색을 할 수 있어요.");
        var result=mapper.readValue(job.getResultJson(),Result.class);
        if(result.verifications()!=null && result.verifications().checkedAt().isAfter(Instant.now().minus(VERIFICATION_REUSE)))
            return new CheckContext(job.getResultJson(),List.of(),result.verifications());
        if(!verificationClient.configured())
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 확인 검색은 OpenAI 연결 설정 후 이용할 수 있어요.");
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        return new CheckContext(job.getResultJson(),checkTargets(VerificationQuestions.of(input,result)),null);
    }
    /** 질문마다 장소에 저장해 둔 같은 질문의 답을 찾아 붙인다. */
    @Transactional(readOnly=true,timeout=10)
    public List<CheckTarget> checkTargets(List<CheckQuestion> questions) {
        Map<Long,Place> saved=new HashMap<>();
        places.findAllById(questions.stream().map(CheckQuestion::placeId).distinct().toList()).forEach(p->saved.put(p.getId(),p));
        var now=Instant.now();
        return questions.stream().map(q->{
            var place=saved.get(q.placeId());
            var stored=place!=null && place.getName().equals(q.name())?PlaceCheckDetails.read(place,q.topic(),q.question(),now,mapper):null;
            return new CheckTarget(q,stored);
        }).toList();
    }
    /** 새로 찾은 답을 장소에 보관한다. 초안을 만든 뒤 장소 이름이 바뀌었으면 보관하지 않는다. */
    @Transactional(timeout=10)
    public void storePlaceChecks(List<SearchedCheck> searched) {
        for(var item:searched) places.lockById(item.placeId()).ifPresent(place->{
            if(!place.getName().equals(item.name())) return;
            String json=PlaceCheckDetails.write(place,item.answer(),mapper);
            if(json!=null) place.updateDetails(json);
        });
        places.flush();
    }
    /** 검색하는 동안 초안이 바뀌지 않았을 때만 결과를 붙인다. */
    @Transactional(timeout=10)
    public void saveVerifications(UUID id,String login,byte[] hash,String expectedResult,Verifications verifications) {
        access.owned(id,login,hash,"ITINERARY");
        var job=jobs.lockById(id).orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
        if(!"COMPLETED".equals(job.getStatus()) || !Objects.equals(job.getResultJson(),expectedResult))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"AI 확인 검색 중에 일정이 바뀌었어요. 다시 시도해 주세요.");
        job.attachToResult(mapper.writeValueAsString(mapper.readValue(expectedResult,Result.class).withVerifications(verifications)));
        jobs.flush();
    }

    /** 후보 목록(개수 제한) 밖의 관광지: 이 지역의 사용 중인 관광지이고 24시간 안에 확인한 카카오 장소만 쓴다. */
    private java.util.Optional<PlaceCandidate> extraCandidate(Long id,PlaceCandidateView pool) {
        return places.findById(id).filter(Place::isActive).filter(p->"ATTRACTION".equals(p.getPlaceType()))
            .filter(p->pool.destination().region().equals(p.getRegion()))
            .filter(p->p.getVerifiedAt()!=null && p.getVerifiedAt().isAfter(KoreaTime.now().minusHours(24)))
            .map(candidates::candidate);
    }
    /**
     * 원본 초안에서 빠지면 안 되던 관광지. 새 초안이면 일정에 쓰기로 고른 관광지 전체, 수정안이면 유지·새로 넣은 곳이다.
     * 남는 시간에 더한 예비 관광지는 여기에 들지 않는다.
     */
    private static Set<Long> firmPlaceIds(Input source) {
        Set<Long> ids=new HashSet<>();
        if(source.revision()==null) source.places().attractions().forEach(p->ids.add(p.placeId()));
        else {
            source.revision().kept().forEach(p->ids.add(p.placeId()));
            source.revision().added().forEach(p->ids.add(p.placeId()));
        }
        return ids;
    }

    /**
     * 관광지를 뺀 뒤 날짜별로 그대로 둘 일정. placed는 낮 관광지라, 방문 중 여기에 없는 곳은 저녁 관광이다.
     * 관광지가 하나도 남지 않는 날이 있으면 다른 날에서 옮겨 와야 하므로 null이다(그때는 일정을 처음부터 다시 배치한다).
     */
    private static List<KeptDay> keptDays(Result result,List<PlaceCandidate> placed,Set<Long> removed) {
        Set<Long> daytime=new HashSet<>(); placed.forEach(p->daytime.add(p.placeId()));
        var days=new ArrayList<KeptDay>();
        for(var day:result.days()) {
            var visits=new ArrayList<Long>(); var meals=new ArrayList<Long>(); Long evening=null;
            for(var item:day.items()) {
                if("MEAL".equals(item.type())) meals.add(item.placeId());
                else if("VISIT".equals(item.type()) && !daytime.contains(item.placeId())) evening=item.placeId();
                else if("VISIT".equals(item.type()) && !removed.contains(item.placeId())) visits.add(item.placeId());
            }
            if(visits.isEmpty()) return null;
            days.add(new KeptDay(List.copyOf(visits),List.copyOf(meals),evening));
        }
        return List.copyOf(days);
    }

    /** 초안에 실제로 배치된 관광지. 시간이 부족하거나 너무 멀어 빠진 관광지(skipped)는 유지·교체 대상에서 뺀다. */
    public static List<PlaceCandidate> placed(Input input,Result result) {
        // 예비 관광지로 대신 넣은 곳도 포함해, 일정에 실제로 방문으로 들어간 관광지만 돌려준다.
        // 저녁 관광은 숙소 근처에서 따로 고르는 곳이라 유지·교체 대상이 아니다(수정안을 만들면 다시 고른다).
        var visited=result.days().stream().flatMap(d->d.items().stream()).filter(i->"VISIT".equals(i.type())).map(Item::placeId).toList();
        var daytime=new ArrayList<>(input.places().attractions()); daytime.addAll(input.reserve());
        return daytime.stream().filter(p->visited.contains(p.placeId())).toList();
    }
    /** 원본 초안이 쓰려던 관광지(고른 곳·실제 방문한 예비 관광지·뺀 곳). 수정안의 새 후보에서 뺀다. */
    private static List<Long> tried(Input input,Result result) {
        var ids=new ArrayList<>(input.places().attractions().stream().map(PlaceCandidate::placeId).toList());
        placed(input,result).forEach(p->ids.add(p.placeId()));
        result.skipped().forEach(p->ids.add(p.placeId()));
        return ids;
    }

    private static boolean indoor(PlaceCandidate place) {
        return INDOOR.matcher(Objects.toString(place.name(),"")+" "+Objects.toString(place.subtype(),"")+" "+Objects.toString(place.description(),"")).find();
    }
    private static List<PlaceRef> refs(List<PlaceCandidate> places) {
        return places.stream().map(p->new PlaceRef(p.placeId(),p.name())).toList();
    }
    /** 목록에서 장소 ID가 같은 후보. 없으면 null이다. 확인 시각·운영정보가 달라도 같은 장소로 본다. */
    private static PlaceCandidate findPlace(List<PlaceCandidate> places,Long placeId) {
        return places.stream().filter(p->p.placeId().equals(placeId)).findFirst().orElse(null);
    }

    @Transactional(readOnly=true,timeout=10)
    public View view(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        return new View(id,job.getStatus(),job.getMessage(),Set.of("QUEUED","GENERATING_SCHEDULE","VALIDATING_SCHEDULE").contains(job.getStatus()),
            mapper.readValue(job.getRequestJson(),Input.class),job.getResultJson()==null?null:mapper.readValue(job.getResultJson(),Result.class),
            job.getExpiresAt());
    }
    @Transactional(readOnly=true)
    public List<UUID> queued() { return jobs.findTop5ByJobTypeAndStatusOrderByCreatedAtAsc("ITINERARY","QUEUED").stream().map(AiJob::getId).toList(); }

    @Transactional(timeout=10)
    public Work claim(UUID id) {
        var job=jobs.lockById(id).orElse(null);
        if(job==null || !"ITINERARY".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        if(!available(job)) {
            job.fail("SCHEDULE_UNAVAILABLE","요청이 만료됐거나 처리할 수 없어요. 조건을 확인해 다시 요청해 주세요."); return null;
        }
        // 대기 제한(5분)은 처리 제한(시작 후 2분, complete)과 따로 잰다. 앞선 요청이 많아 오래 기다렸을 때만 여기서 끝낸다.
        if(job.getCreatedAt().isBefore(now().minus(RecommendationJobService.WAIT_LIMIT))) {
            job.fail("TIMEOUT",RecommendationJobService.WAIT_MESSAGE); return null;
        }
        job.generatingSchedule(); return new Work(id,mapper.readValue(job.getRequestJson(),Input.class));
    }
    // 외부 지도 호출 동안 트랜잭션이나 DB 잠금을 유지하지 않는다. AI 보조와 주변 지역 추천도 같은 단계에서 처리한다.
    public Result generate(Work work) {
        // 작업 처리 제한은 2분이다. 배치에 쓴 시간을 빼고 남는 동안만 AI 보조를 시도한다.
        var deadline=Instant.now().plusSeconds(100);
        var draft=generator.generate(work.input());
        // 반려동물 동반 여행이면 일정에 들어간 장소의 관광공사 동반 정보를 먼저 찾아 AI 안내와 조건 확인에 쓴다. 실패해도 초안은 그대로다.
        if(PetTravel.wanted(work.input().conditions())) {
            var petService=pets.getIfAvailable();
            if(petService!=null) try { draft=draft.withPets(petService.lookup(work.input(),draft,Instant.now().plusSeconds(25))); }
            catch(RuntimeException error) { log.warn("반려동물 동반 정보 조회를 건너뜀 job={} ({})",work.id(),error.toString()); }
        }
        // 자동 실행 설정이면 확인 필요 항목(반려동물 동반·여행 목적 활동)을 AI 웹 검색으로 찾아 AI 준비 안내에도 넘긴다. 유료라 기본은 버튼이다.
        if(verification.auto() && verificationClient.configured()) {
            try { draft=draft.withVerifications(verifyNow(work.input(),draft,Instant.now().plusSeconds(40))); }
            catch(RuntimeException error) { log.warn("AI 확인 검색을 건너뜀 job={} ({})",work.id(),error.toString()); }
        }
        // 일정에 들어간 관광지의 관광공사 소개문을 찾아 AI 추천 활동의 근거로 넘긴다(2026-10-31). 최대 12초만 쓰고
        // AI 안내에 쓸 시간(60초)을 남긴다. 처음 조회한 소개문은 보관해 다음 초안부터는 다시 부르지 않는다. 실패해도 초안은 그대로다.
        Map<Long,String> overviews=Map.of();
        var overviewService=placeOverviews.getIfAvailable();
        var overviewUntil=Instant.now().plusSeconds(12);
        if(overviewUntil.isAfter(deadline.minusSeconds(60))) overviewUntil=deadline.minusSeconds(60);
        if(overviewService!=null && Instant.now().isBefore(overviewUntil)) {
            try { overviews=overviewService.lookup(work.input(),draft,overviewUntil); }
            catch(RuntimeException error) { log.warn("장소 소개문 조회를 건너뜀 job={} ({})",work.id(),error.toString()); }
        }
        var plan=assistant.assist(work.input(),draft,deadline,overviews);
        // 주변 지역 조회는 최대 수십 초가 걸릴 수 있어 남은 시간이 부족하면 건너뛴다. 실패해도 일정 초안은 그대로다.
        if(Instant.now().isAfter(deadline.minusSeconds(40))) return plan;
        try { return plan.withNearby(nearby.recommend(work.input(),plan)); }
        catch(RuntimeException error) { return plan; }
    }

    @Transactional(timeout=10)
    public void complete(Work work,Result result) {
        var job=jobs.lockById(work.id()).orElse(null);
        if(job==null || !"GENERATING_SCHEDULE".equals(job.getStatus()) || !"ITINERARY".equals(job.getJobType())) return;
        if(!available(job) || job.getStartedAt().isBefore(now().minusMinutes(2))) { job.fail("SCHEDULE_TIMEOUT","요청이 만료되거나 처리 시간이 초과됐어요. 다시 요청해 주세요."); return; }
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        if(!input.equals(work.input()) || !unchanged(input)) { job.fail("PLACES_CHANGED","장소 정보가 변경됐어요. 장소 목록에서 다시 요청해 주세요."); return; }
        job.validatingSchedule(); generator.validate(input,result);
        job.completeSchedule(mapper.writeValueAsString(result)); jobs.flush();
    }
    @Transactional(timeout=10)
    public void fail(Work work,String code,String message) {
        jobs.lockById(work.id()).filter(j->"ITINERARY".equals(j.getJobType()) && "GENERATING_SCHEDULE".equals(j.getStatus()))
            .ifPresent(j->j.fail(code,message));
    }
    /**
     * 일정을 만드는 동안 장소가 바뀌지 않았는지 본다. 이름·주소·좌표·출처·활성·유형이 같고 지금도 쓸 수 있는 장소(usable)면 그대로로 본다.
     * 확인 시각(verifiedAt)은 같은 지역 장소를 누군가 다시 조회하면 바뀌므로 "같음" 대신 "지금도 확인 유효"인지만 본다.
     * 운영정보도 비교하지 않는다. 다른 사람이 장소 상세를 열 때 새로 조회돼 바뀔 수 있고, 일정의 운영시간 판단은 작업 입력의 정보로 이미 했기 때문이다.
     * 둘을 그대로 비교하면 같은 지역을 다른 사람이 조회했을 뿐인데 만들던 초안·수정안이 마지막에 실패했다.
     */
    private boolean unchanged(Input input) {
        var pool=new ArrayList<>(input.attractionsWithReserve()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var destination=input.places().destination();
        if(places.findById(destination.placeId()).filter(p->p.isActive() && "DESTINATION".equals(p.getPlaceType())
                && destination.name().equals(p.getName()) && destination.region().equals(p.getRegion())).isEmpty()) return false;
        for(var candidate:pool) {
            String type=input.attractionsWithReserve().contains(candidate)?"ATTRACTION":input.places().restaurants().contains(candidate)?"RESTAURANT":"ACCOMMODATION";
            if(places.findById(candidate.placeId()).filter(p->type.equals(p.getPlaceType())
                && candidates.usable(p,destination.region()) && candidate.name().equals(p.getName())
                && candidate.address().equals(p.getAddress()) && candidate.sourceUrl().equals(p.getSourceUrl())
                && (candidate.longitude()==null || (p.getLongitude()!=null && Double.compare(candidate.longitude(),p.getLongitude().doubleValue())==0))
                && (candidate.latitude()==null || (p.getLatitude()!=null && Double.compare(candidate.latitude(),p.getLatitude().doubleValue())==0))).isEmpty()) return false;
        }
        return true;
    }
    private boolean available(AiJob job) {
        return job.getExpiresAt().isAfter(now()) && (job.getUserId()==null || users.findById(job.getUserId()).filter(u->u.getStatus()==UserStatus.ACTIVE).isPresent());
    }
    private KakaoMapClient.Transport confirmedTransport(ConfirmedTravelConditions conditions) {
        for(var keyword:conditions.selectedKeywords()) if("TRANSPORTATION".equals(keyword.categoryCode())) {
            String value=mapper.readTree(keyword.valueJson()).path("value").asText();
            if("CAR".equals(value)) return KakaoMapClient.Transport.CAR;
            if("PUBLIC_TRANSIT".equals(value)) return KakaoMapClient.Transport.PUBLIC_TRANSIT;
        }
        return null;
    }
    private LocalDateTime now() { return KoreaTime.now(); }
}
