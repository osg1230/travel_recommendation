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
    private final ObjectMapper mapper;
    public record Work(UUID id,Input input) {}

    @Transactional(readOnly=true,timeout=10)
    public Input options(UUID parentId,long destinationId,String login,byte[] hash) {
        var pool=candidates.find(parentId,destinationId,login,hash);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        var conditions=mapper.readValue(parent.getRequestJson(),ConfirmedTravelConditions.class);
        int days=conditions.durationDays()==null?1:Math.max(1,Math.min(7,conditions.durationDays()));
        // 후보가 늘어도 전부 방문시키지 않는다. 기간당 최대 2곳, 전체 8곳으로 조회 비용·체류량을 제한한다.
        pool=new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),pool.accommodationNeeded(),
            pool.attractions().stream().limit(Math.min(8,days*2)).toList(),pool.accommodations(),pool.restaurants());
        var transport=confirmedTransport(conditions);
        return new Input(parentId,pool,conditions,LocalTime.of(9,0),LocalTime.of(18,0),
            transport==null?KakaoMapClient.Transport.CAR:transport,90);
    }

    @Transactional(timeout=10)
    public UUID create(UUID parentId,long destinationId,String login,byte[] hash,LocalTime start,LocalTime end,
                       KakaoMapClient.Transport transport,int visitMinutes) {
        var current=options(parentId,destinationId,login,hash);
        var fixed=confirmedTransport(current.conditions());
        if(fixed!=null && fixed!=transport) throw new ResponseStatusException(HttpStatus.CONFLICT,"확정한 이동수단과 달라요. 여행 조건에서 먼저 수정해 주세요.");
        var input=new Input(parentId,current.places(),current.conditions(),start,end,transport,visitMinutes);
        generator.checkInput(input);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }

    /** 교체 사유. 실내 변경만 후보 선택 순서에 반영하고 나머지는 기록·안내에만 사용한다. */
    public enum ReplaceReason {
        TASTE("취향과 맞지 않아요"), INDOOR("실내 장소로 바꾸고 싶어요"), VISITED("이미 가 본 곳이에요"), OTHER("기타");
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
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(source.getStatus()) || source.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 수정안을 만들 수 있어요.");
        var original=mapper.readValue(source.getRequestJson(),Input.class);
        var chosen=new LinkedHashSet<>(replace==null?List.<Long>of():replace);
        var originalIds=original.places().attractions().stream().map(PlaceCandidate::placeId).toList();
        if(!originalIds.containsAll(chosen))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 초안에 있는 관광지만 교체할 수 있어요.");
        if(chosen.size()==originalIds.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"유지할 관광지를 한 곳 이상 남겨 주세요. 모두 바꾸려면 일정을 새로 만들어 주세요.");
        if(!chosen.isEmpty() && reason==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"교체 이유를 골라 주세요.");
        long destinationId=original.places().destination().placeId();
        // 현재 확인된 후보 전체를 다시 읽는다. 오래된 초안의 장소 정보로 수정안을 만들지 않는다.
        var pool=candidates.find(original.recommendationId(),destinationId,login,hash);
        var current=options(original.recommendationId(),destinationId,login,hash);
        var kept=new ArrayList<PlaceCandidate>();
        for(var place:original.places().attractions()) {
            if(chosen.contains(place.placeId())) continue;
            kept.add(pool.attractions().stream().filter(p->p.placeId().equals(place.placeId())).findFirst().orElseThrow(()->
                new ResponseStatusException(HttpStatus.CONFLICT,"유지한 관광지 '"+place.name()+"'의 정보가 바뀌었어요. 장소 목록에서 다시 조회해 주세요.")));
        }
        var spare=pool.attractions().stream().filter(p->!originalIds.contains(p.placeId())).toList();
        if(reason==ReplaceReason.INDOOR)
            spare=spare.stream().sorted(Comparator.comparingInt(p->indoor(p)?0:1)).toList();
        if(spare.size()<chosen.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"바꿀 수 있는 다른 관광지 후보가 부족해요. 장소 목록에서 후보를 더 찾아본 뒤 다시 시도해 주세요.");
        var added=spare.subList(0,chosen.size());
        var attractions=new ArrayList<>(kept); attractions.addAll(added);
        var places=new PlaceCandidateView(current.places().jobId(),current.places().destination(),current.places().durationDays(),
            current.places().accommodationNeeded(),List.copyOf(attractions),current.places().accommodations(),current.places().restaurants());
        var revision=new Revision(sourceId,refs(kept),refs(original.places().attractions().stream().filter(p->chosen.contains(p.placeId())).toList()),
            refs(added),chosen.isEmpty()?null:reason.name(),Instant.now());
        var input=new Input(original.recommendationId(),places,current.conditions(),start,end,original.transport(),visitMinutes,revision);
        generator.checkInput(input);
        var parent=access.owned(original.recommendationId(),login,hash,"RECOMMENDATION");
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }
    private static boolean indoor(PlaceCandidate place) {
        return INDOOR.matcher(Objects.toString(place.name(),"")+" "+Objects.toString(place.subtype(),"")+" "+Objects.toString(place.description(),"")).find();
    }
    private static List<PlaceRef> refs(List<PlaceCandidate> places) {
        return places.stream().map(p->new PlaceRef(p.placeId(),p.name())).toList();
    }

    @Transactional(readOnly=true,timeout=10)
    public View view(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        return new View(id,job.getStatus(),job.getMessage(),Set.of("QUEUED","GENERATING_SCHEDULE","VALIDATING_SCHEDULE").contains(job.getStatus()),
            mapper.readValue(job.getRequestJson(),Input.class),job.getResultJson()==null?null:mapper.readValue(job.getResultJson(),Result.class));
    }
    @Transactional(readOnly=true)
    public List<UUID> queued() { return jobs.findTop5ByJobTypeAndStatusOrderByCreatedAtAsc("ITINERARY","QUEUED").stream().map(AiJob::getId).toList(); }

    @Transactional(timeout=10)
    public Work claim(UUID id) {
        var job=jobs.lockById(id).orElse(null);
        if(job==null || !"ITINERARY".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        if(!available(job) || job.getCreatedAt().isBefore(now().minusMinutes(2))) {
            job.fail("SCHEDULE_UNAVAILABLE","요청이 만료됐거나 처리할 수 없어요. 조건을 확인해 다시 요청해 주세요."); return null;
        }
        job.generatingSchedule(); return new Work(id,mapper.readValue(job.getRequestJson(),Input.class));
    }
    // 외부 지도 호출 동안 트랜잭션이나 DB 잠금을 유지하지 않는다. AI 보조도 같은 단계에서 처리한다.
    public Result generate(Work work) {
        // 작업 처리 제한은 2분이다. 배치에 쓴 시간을 빼고 남는 동안만 AI 보조를 시도한다.
        var deadline=Instant.now().plusSeconds(100);
        var plan=generator.generate(work.input());
        return assistant.assist(work.input(),plan,deadline);
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
    private boolean unchanged(Input input) {
        var pool=new ArrayList<>(input.places().attractions()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var destination=input.places().destination();
        if(places.findById(destination.placeId()).filter(p->p.isActive() && "DESTINATION".equals(p.getPlaceType())
                && destination.name().equals(p.getName()) && destination.region().equals(p.getRegion())).isEmpty()) return false;
        for(var candidate:pool) {
            String type=input.places().attractions().contains(candidate)?"ATTRACTION":input.places().restaurants().contains(candidate)?"RESTAURANT":"ACCOMMODATION";
            if(places.findById(candidate.placeId()).filter(p->p.isActive() && type.equals(p.getPlaceType())
                && destination.region().equals(p.getRegion()) && candidate.name().equals(p.getName())
                && candidate.address().equals(p.getAddress()) && candidate.sourceUrl().equals(p.getSourceUrl())
                && candidate.verifiedAt().equals(p.getVerifiedAt())
                && Objects.equals(candidate.operatingInfo(),PlaceOperatingDetails.read(p,mapper))
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
    private LocalDateTime now() { return LocalDateTime.now(ZoneOffset.UTC); }
}
