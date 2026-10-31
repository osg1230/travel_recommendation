package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.MemberStats;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import com.travel.travel_recommendation.dto.ItineraryPlan.Input;
import com.travel.travel_recommendation.repository.AiJobRepository;
import com.travel.travel_recommendation.repository.PlaceRepository;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 일정 작업이 시작되기 전에, 관광지 후보 전체를 GPT에 보여 주고 갈 만한 순서와 뺄 곳을 받아 일정에 넣을 관광지를 다시 고른다.
 * 서버 규칙(중심 장소와의 거리·분류 글자)만으로는 덜 알려진 곳이 종류가 드물다는 이유로 먼저 뽑혔기 때문이다.
 * GPT는 확인된 후보 안에서 순서만 정하고, 몇 곳을 넣을지와 시각·동선 배치는 지금처럼 서버가 정한다.
 * 주변 음식점 찾기와 같이 읽기 → 외부 호출(DB 연결 없음) → 저장을 나눈다. 실패하면 작업을 만들 때 서버 규칙으로 고른 관광지를 그대로 쓴다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PlacePickService {
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(PlacePickService.class);
    private final AiJobRepository jobs;
    private final PlaceRepository places;
    private final OpenAiPlacePickClient client;
    private final ItineraryGenerator generator;
    private final ObjectMapper mapper;
    private final ObjectProvider<PlacePickService> self;
    private final SimilarMemberService similarMembers;

    /** members는 관광지 ID별로 비슷한 조건으로 여행한 다른 회원의 기록이다. 기록이 없으면 비어 있다. */
    public record Task(UUID jobId, String requestJson, Input input, Map<Long,MemberStats> members) {
        public Task(UUID jobId, String requestJson, Input input) { this(jobId,requestJson,input,Map.of()); }
    }

    /** 대기 중인 일정 작업의 관광지를 AI 순서로 다시 고른다. 이미 골랐거나 후보가 없으면 요청을 바꾸지 않는다. */
    public void enrich(UUID jobId) {
        if(!client.configured()) return;
        var task=self.getObject().task(jobId);
        if(task==null) return;
        var input=task.input();
        var picks=client.pick(input.conditions(),input.places().destination().name(),input.transport().name(),candidates(input,task.members()));
        self.getObject().save(task,picks);
    }

    @Transactional(readOnly=true,timeout=10)
    public Task task(UUID jobId) {
        var job=jobs.findById(jobId).orElse(null);
        if(job==null || !"ITINERARY".equals(job.getJobType()) || !"QUEUED".equals(job.getStatus())) return null;
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        // 처음 만드는 초안만 다시 고른다. 수정안은 사용자가 본 초안의 장소를 유지해야 한다.
        if(input.revision()!=null || input.picking()==null || input.picking().byAi() || input.picking().pool().isEmpty()) return null;
        // 순서를 정할 때 참고할 비슷한 조건 회원 기록. 본인 기록은 뺀다.
        var members=similarMembers.places(input.conditions(),job.getUserId(),input.picking().pool().stream().map(PlaceCandidate::placeId).toList());
        return new Task(jobId,job.getRequestJson(),input,members);
    }

    static List<OpenAiPlacePickClient.Candidate> candidates(Input input) { return candidates(input,Map.of()); }

    /**
     * GPT에 보낼 후보. 필수·중심 장소는 fixed로 표시하고, 중심 장소(없으면 후보들의 가운데)에서의 직선거리를 함께 준다.
     * 비슷한 조건 회원 기록이 있는 관광지에는 회원 수도 붙인다.
     */
    static List<OpenAiPlacePickClient.Candidate> candidates(Input input,Map<Long,MemberStats> members) {
        var pool=input.picking().pool();
        var c=input.constraints();
        Set<Long> fixed=new HashSet<>();
        if(c!=null) { fixed.addAll(c.requiredPlaceIds()); fixed.addAll(c.centerPlaceIds()); }
        var centers=c==null?List.<PlaceCandidate>of():pool.stream()
            .filter(p->c.centerPlaceIds().contains(p.placeId()) && p.latitude()!=null && p.longitude()!=null).toList();
        // 중심 장소가 없으면 가운데에 가장 가까운 후보를 기준으로 삼는다.
        if(centers.isEmpty()) centers=TripConstraints.nearCenterFirst(pool).stream().filter(p->p.latitude()!=null && p.longitude()!=null).limit(1).toList();
        final var from=centers;
        return pool.stream().map(p->new OpenAiPlacePickClient.Candidate(p.placeId(),p.name(),p.subtypeLabel(),
            p.latitude()==null || p.longitude()==null || from.isEmpty()?null
                :(int)Math.round(from.stream().mapToDouble(center->kilometers(center,p)).min().orElse(0)),
            fixed.contains(p.placeId()),members.get(p.placeId()))).toList();
    }

    /**
     * 요청이 그대로일 때만 AI 순서로 다시 고른 관광지를 요청에 넣는다. 사용자가 정한 필수·중심 장소와 하루 방문 수는 그대로 지킨다.
     * 다시 고른 결과가 조건과 맞지 않으면(예: 필수 조건 충돌) 바꾸지 않는다.
     */
    @Transactional(timeout=10)
    public void save(Task task,OpenAiPlacePickClient.Picks picks) {
        var job=jobs.lockById(task.jobId()).orElse(null);
        if(job==null || !"QUEUED".equals(job.getStatus()) || !task.requestJson().equals(job.getRequestJson())) return;
        Input picked;
        try {
            picked=picked(task.input(),picks);
            generator.checkInput(picked);
        } catch(RuntimeException error) {
            log.warn("AI 순서로 관광지를 다시 고르지 못해 기존 선택을 씁니다 job={} ({})",task.jobId(),error.toString());
            return;
        }
        job.attachItineraryRequest(mapper.writeValueAsString(picked));
        jobs.flush();
    }

    /**
     * AI 순서(picks)로 일정에 넣을 관광지·예비 관광지·저녁 관광 후보를 다시 고른 입력. 조건과 맞지 않으면 예외가 난다.
     * AI가 밤에 둘러보기 좋다고 꼽은 곳은 숙박하는 밤 수만큼 저녁 관광 후보 맨 앞에 두고 그 ID를 남긴다(2026-10-31).
     */
    static Input picked(Input input,OpenAiPlacePickClient.Picks picks) {
        var c=input.constraints();
        var ordered=TripConstraints.byPicks(input.picking().pool(),c==null?List.of():c.centerPlaceIds(),picks.order(),picks.skip());
        // '다른 관광지로 다시 만들기'면 AI 순서에서도 직전 초안의 관광지를 뒤로 보낸다. 필수·중심 장소는 그대로 둔다.
        Set<Long> keep=new HashSet<>();
        if(c!=null) { keep.addAll(c.requiredPlaceIds()); keep.addAll(c.centerPlaceIds()); }
        var previous=input.picking().previous();
        ordered=TripConstraints.previousLast(ordered,previous,keep,Set.copyOf(picks.skip()));
        int days=input.conditions().durationDays()==null?1:input.conditions().durationDays();
        // 직전 초안에 있던 밤 명소는 이번에는 아끼지 않는다(다시 만들기는 다른 곳부터 고른다).
        var night=picks.night().stream().filter(id->!previous.contains(id)).toList();
        var arranged=ItineraryJobService.withoutPreviousEvening(ItineraryJobService.arrange(ordered,Set.copyOf(picks.skip()),night,true,
            input.places().accommodationNeeded(),days,input.start(),input.end(),input.visitMinutes(),c),previous);
        // 저녁 관광 후보에 실제로 들어간 밤 명소만 남겨, 일정 생성이 저녁에 그곳을 먼저 보게 한다.
        var kept=arranged.evening().stream().map(PlaceCandidate::placeId).filter(night::contains).toList();
        return input.withPicked(arranged.selected(),arranged.reserve(),arranged.evening(),kept);
    }

    private static double kilometers(PlaceCandidate a,PlaceCandidate b) {
        double x=(a.longitude()-b.longitude())*Math.cos(Math.toRadians(a.latitude()));
        double y=a.latitude()-b.latitude();
        return Math.sqrt(x*x+y*y)*111.0;
    }
}
