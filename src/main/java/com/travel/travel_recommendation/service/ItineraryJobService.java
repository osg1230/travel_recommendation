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

    /** 필수 방문 장소로 고를 수 있는 전체 관광지 후보(최대 16곳). */
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
        current=new Input(parentId,candidates.findForSchedule(parentId,destinationId,login,hash,c),c,
            current.start(),current.end(),current.transport(),current.visitMinutes());
        var fixed=confirmedTransport(current.conditions());
        if(fixed!=null && fixed!=transport) throw new ResponseStatusException(HttpStatus.CONFLICT,"확정한 이동수단과 달라요. 여행 조건에서 먼저 수정해 주세요.");
        if(constraints!=null && constraints.budgetBasis()!=null && current.conditions().budget()==null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"예산을 정하지 않아 예산 기준은 고를 수 없어요.");
        // 숙소 예산대는 예산을 정하지 않았고 숙소가 필요한 여행에서만 고른다(SFR-03-03).
        if(constraints!=null && constraints.stayTier()!=null && (current.conditions().budget()!=null || !current.places().accommodationNeeded()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"숙소 예산대는 예산을 정하지 않은 숙박 여행에서만 고를 수 있어요.");
        var places=current.places();
        int days=current.conditions().durationDays()==null?1:current.conditions().durationDays();
        // 시간 범위 자체의 검증은 generator.checkInput이 한다. 여기서는 조건 선택에 필요한 값만 먼저 확인한다.
        if(start==null || end==null || (constraints!=null && !end.isAfter(start)))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"하루 시작·종료 시간을 확인해 주세요.");
        // 하루 시간·체류시간으로 추정한 수만큼 관광지를 고른다. 실제로 다 들어가는지는 일정 생성 때 이동시간으로 다시 확인한다.
        List<PlaceCandidate> selected;
        // 가운데에서 가까운 곳부터, 같은 종류(해수욕장 등)는 돌아가며 고른다.
        var ordered=TripConstraints.mixKinds(TripConstraints.nearCenterFirst(places.attractions()));
        try {
            selected=TripConstraints.select(ordered,days,start,end,visitMinutes,constraints);
        } catch(IllegalArgumentException invalid) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"화면에 있는 장소와 값으로 조건을 다시 골라 주세요.");
        }
        places=new PlaceCandidateView(places.jobId(),places.destination(),places.durationDays(),places.accommodationNeeded(),
            selected,places.accommodations(),places.restaurants());
        var input=new Input(parentId,places,current.conditions(),start,end,transport,visitMinutes,null,constraints,reserve(ordered,selected));
        generator.checkInput(input);
        var parent=access.owned(parentId,login,hash,"RECOMMENDATION");
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }

    /** 예비 관광지 수. 섬처럼 갈 수 없어 빠진 곳을 채울 만큼만 둔다. */
    static final int MAX_RESERVE=6;
    /** 고르지 않은 후보 중 앞에서부터(가운데 가까운 곳·종류를 섞은 순서) 예비 관광지로 둔다. */
    static List<PlaceCandidate> reserve(List<PlaceCandidate> ordered,List<PlaceCandidate> selected) {
        var ids=selected.stream().map(PlaceCandidate::placeId).toList();
        return ordered.stream().filter(p->!ids.contains(p.placeId())).limit(MAX_RESERVE).toList();
    }

    private TripConstraints.ConflictException scheduleConflict(String message) {
        return new TripConstraints.ConflictException(List.of(new TripConstraints.Conflict(message,
            List.of("아래 여행 기간·숙박 여부를 선택하거나 추천 화면의 조건 수정에서 변경해 주세요."))));
    }

    /** 교체 사유. 실내 변경만 후보 선택 순서에 반영하고 나머지는 기록·안내에만 사용한다. */
    public enum ReplaceReason {
        TASTE("취향과 맞지 않아요"), INDOOR("실내 장소로 바꾸고 싶어요"), VISITED("이미 가 본 곳이에요"), OTHER("기타"),
        AI_REQUEST("문장으로 요청");
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
        if(reason==ReplaceReason.AI_REQUEST) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"교체 이유를 다시 골라 주세요.");
        return revise(sourceId,login,hash,replace,null,reason,start,end,visitMinutes,null,null);
    }

    /** add가 있으면 그 후보로 순서대로 바꾸고, 없으면 기존 규칙(후보 순서·실내 우선)으로 고른다. */
    private UUID revise(UUID sourceId,String login,byte[] hash,List<Long> replace,List<Long> add,ReplaceReason reason,
                        LocalTime start,LocalTime end,int visitMinutes,String request,String explanation) {
        var source=access.owned(sourceId,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(source.getStatus()) || source.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 수정안을 만들 수 있어요.");
        var original=mapper.readValue(source.getRequestJson(),Input.class);
        var originalResult=mapper.readValue(source.getResultJson(),Result.class);
        var placed=placed(original,originalResult);
        var chosen=new LinkedHashSet<>(replace==null?List.<Long>of():replace);
        var originalIds=placed.stream().map(PlaceCandidate::placeId).toList();
        if(!originalIds.containsAll(chosen))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"이 초안에 있는 관광지만 교체할 수 있어요.");
        if(chosen.size()==originalIds.size())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"유지할 관광지를 한 곳 이상 남겨 주세요. 모두 바꾸려면 일정을 새로 만들어 주세요.");
        if(!chosen.isEmpty() && reason==null)
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
            kept.add(pool.attractions().stream().filter(p->p.placeId().equals(place.placeId())).findFirst().orElseThrow(()->
                new ResponseStatusException(HttpStatus.CONFLICT,"유지한 관광지 '"+place.name()+"'의 정보가 바뀌었어요. 장소 목록에서 다시 조회해 주세요.")));
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
            // AI가 고른 후보는 현재 확인된 후보 중 원본에 없던 곳이어야 한다.
            var byId=new LinkedHashMap<Long,PlaceCandidate>(); spare.forEach(p->byId.put(p.placeId(),p));
            if(add.size()!=chosen.size() || !byId.keySet().containsAll(add) || new HashSet<>(add).size()!=add.size())
                throw new ResponseStatusException(HttpStatus.CONFLICT,"수정안의 후보가 바뀌었어요. 다시 요청해 주세요.");
            spare=add.stream().map(byId::get).toList();
        }
        if(spare.size()<chosen.size())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"바꿀 수 있는 다른 관광지 후보가 부족해요. 장소 목록에서 후보를 더 찾아본 뒤 다시 시도해 주세요.");
        var added=spare.subList(0,chosen.size());
        var attractions=new ArrayList<>(kept); attractions.addAll(added);
        var places=new PlaceCandidateView(pool.jobId(),pool.destination(),pool.durationDays(),
            pool.accommodationNeeded(),List.copyOf(attractions),pool.accommodations(),pool.restaurants());
        var revision=new Revision(sourceId,refs(kept),refs(placed.stream().filter(p->chosen.contains(p.placeId())).toList()),
            refs(added),chosen.isEmpty() && reason!=ReplaceReason.AI_REQUEST?null:reason.name(),Instant.now(),request,explanation);
        // 새로 넣지 않고 남은 후보는 이 수정안의 예비 관광지로 둔다.
        var spareLeft=spare.subList(chosen.size(),spare.size()).stream().limit(MAX_RESERVE).toList();
        var input=new Input(original.recommendationId(),places,original.conditions(),start,end,original.transport(),visitMinutes,revision,original.constraints(),spareLeft);
        generator.checkInput(input);
        var parent=access.owned(original.recommendationId(),login,hash,"RECOMMENDATION");
        return jobs.saveAndFlush(AiJob.itinerary(parent,mapper.writeValueAsString(input))).getId();
    }
    /** AI 수정 결과. job이 있으면 새 수정안 작업, question이 있으면 일정을 바꾸지 않고 확인할 질문이다. */
    public record AiRevision(UUID job,String question) {}

    /**
     * 자연어 수정 요청(SFR-08-04)을 GPT로 수정안 형식으로 바꾼 뒤 기존 수정안 흐름으로 새 초안을 만든다.
     * 형식이 틀린 응답은 사유를 알려 최대 2회 다시 요청하고, 모호하면 질문만 돌려준다. 원본 초안은 바꾸지 않는다.
     * 외부 호출 동안 트랜잭션을 잡지 않도록 이 메서드 자체에는 트랜잭션을 두지 않는다.
     */
    public AiRevision aiRevise(UUID sourceId,String login,byte[] hash,String request) {
        String text=request==null?"":request.strip();
        if(text.isEmpty() || text.length()>300) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"수정 요청을 1~300자로 적어 주세요.");
        var context=self().aiContext(sourceId,login,hash);
        OpenAiRevisionClient.Proposal proposal=null; String failure=null;
        for(int attempt=0;attempt<3 && proposal==null;attempt++) {
            try {
                proposal=revisions.propose(context.input().conditions(),context.input(),context.result(),context.current(),
                    context.spare(),context.fixed(),text,failure);
            } catch(RecommendationException e) {
                if(!"AI_INVALID_RESPONSE".equals(e.getCode()) || attempt==2) throw e;
                failure=e.getMessage();
            }
        }
        if(proposal.clarify()) return new AiRevision(null,proposal.question());
        var original=context.input();
        LocalTime start=proposal.start()==null?original.start():proposal.start();
        LocalTime end=proposal.end()==null?original.end():proposal.end();
        int visit=proposal.visitMinutes()==null?original.visitMinutes():proposal.visitMinutes();
        if(proposal.replace().isEmpty() && start.equals(original.start()) && end.equals(original.end()) && visit==original.visitMinutes())
            return new AiRevision(null,"바꿀 내용을 찾지 못했어요. 어떤 관광지나 시간을 바꾸고 싶은지 조금 더 구체적으로 적어 주세요.");
        return new AiRevision(self().reviseAi(sourceId,login,hash,proposal.replace(),proposal.add(),start,end,visit,text,proposal.explanation()),null);
    }
    @Transactional(timeout=10)
    public UUID reviseAi(UUID sourceId,String login,byte[] hash,List<Long> replace,List<Long> add,
                         LocalTime start,LocalTime end,int visitMinutes,String request,String explanation) {
        return revise(sourceId,login,hash,replace,add,ReplaceReason.AI_REQUEST,start,end,visitMinutes,request,explanation);
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
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"AI 수정 요청은 OpenAI 연결 설정 후 이용할 수 있어요. 위의 '장소 바꿔서 수정안 만들기'는 그대로 쓸 수 있어요.");
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
    /** 같은 초안을 다시 눌러도 이 시간 안에는 이전 조회 결과를 쓴다. */
    static final Duration PRICE_REUSE=Duration.ofHours(24);
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
        List<PriceQuote> quotes=new ArrayList<>(); List<SearchedPrice> searched=new ArrayList<>();
        try {
            // 장소마다 따로 검색해야 다른 가게 가격이 섞이지 않는다. 앞에서부터 차례로 찾고, 저장해 둔 가격이 있는 곳은 건너뛴다.
            for(var target:context.targets()) {
                if(target.stored()!=null) { quotes.add(target.stored()); continue; }
                var quote=priceClient.lookup(target.place(),target.kind()).checkedAt(Instant.now());
                quotes.add(quote); searched.add(new SearchedPrice(target.place(),quote));
            }
        } finally {
            // 중간에 실패하거나 초안이 바뀌어도 이미 비용을 낸 검색 결과는 장소에 남긴다.
            if(!searched.isEmpty()) {
                try { self().storePlacePrices(searched); }
                catch(RuntimeException e) { log.warn("참고 가격을 장소에 저장하지 못함 job={} ({})",id,e.toString()); }
            }
        }
        var prices=new Prices(quotes,Instant.now());
        self().savePrices(id,login,hash,context.resultJson(),prices);
        return prices;
    }
    @Transactional(readOnly=true,timeout=10)
    public PriceContext priceContext(UUID id,String login,byte[] hash) {
        var job=access.owned(id,login,hash,"ITINERARY");
        if(!"COMPLETED".equals(job.getStatus()) || job.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"완성된 일정 초안에서만 가격을 찾을 수 있어요.");
        var result=mapper.readValue(job.getResultJson(),Result.class);
        if(result.prices()!=null && result.prices().checkedAt().isAfter(Instant.now().minus(PRICE_REUSE)))
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

    /** 초안에 실제로 배치된 관광지. 시간이 부족하거나 너무 멀어 빠진 관광지(skipped)는 유지·교체 대상에서 뺀다. */
    public static List<PlaceCandidate> placed(Input input,Result result) {
        // 예비 관광지로 대신 넣은 곳도 포함해, 일정에 실제로 방문으로 들어간 관광지만 돌려준다.
        var visited=result.days().stream().flatMap(d->d.items().stream()).filter(i->"VISIT".equals(i.type())).map(Item::placeId).toList();
        return input.attractionsWithReserve().stream().filter(p->visited.contains(p.placeId())).toList();
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
    // 외부 지도 호출 동안 트랜잭션이나 DB 잠금을 유지하지 않는다. AI 보조와 주변 지역 추천도 같은 단계에서 처리한다.
    public Result generate(Work work) {
        // 작업 처리 제한은 2분이다. 배치에 쓴 시간을 빼고 남는 동안만 AI 보조를 시도한다.
        var deadline=Instant.now().plusSeconds(100);
        var plan=assistant.assist(work.input(),generator.generate(work.input()),deadline);
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
    private boolean unchanged(Input input) {
        var pool=new ArrayList<>(input.attractionsWithReserve()); pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var destination=input.places().destination();
        if(places.findById(destination.placeId()).filter(p->p.isActive() && "DESTINATION".equals(p.getPlaceType())
                && destination.name().equals(p.getName()) && destination.region().equals(p.getRegion())).isEmpty()) return false;
        for(var candidate:pool) {
            String type=input.attractionsWithReserve().contains(candidate)?"ATTRACTION":input.places().restaurants().contains(candidate)?"RESTAURANT":"ACCOMMODATION";
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
