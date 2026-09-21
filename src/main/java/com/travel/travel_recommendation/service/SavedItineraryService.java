package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.dto.SavedItinerary;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.time.*;
import java.util.*;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

/** 임시 일정 초안을 회원의 내 일정으로 저장하고 조회·제목 수정·삭제를 처리한다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class SavedItineraryService {
    private final RecommendationJobService access;
    private final ItineraryRepository itineraries;
    private final ItineraryItemRepository items;
    private final UserRepository users;
    private final ObjectMapper mapper;
    /** 저장 결과. created=false이면 같은 초안을 이미 저장해 둔 일정이다. */
    public record Saved(Long id, boolean created) {}

    @Transactional(timeout=10)
    public Saved save(UUID jobId, String loginId) {
        Long userId=member(loginId);
        var job=access.owned(jobId,loginId,null,"ITINERARY");
        if(!"COMPLETED".equals(job.getStatus()) || job.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"아직 완성되지 않은 일정이에요. 초안이 준비된 뒤 저장해 주세요.");
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        var result=mapper.readValue(job.getResultJson(),Result.class);
        // 날짜가 없는 초안은 방문 시각을 확정할 수 없어 저장 대상이 아니다.
        if(input.conditions().startDate()==null || result.days().stream().anyMatch(day->day.date()==null))
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "여행 날짜가 정해지지 않은 초안이에요. 여행 조건에서 출발일을 정하고 일정을 다시 만들어 주세요.");
        // 미충족 조건이 남은 초안은 완료 일정으로 저장하지 않는다(SFR-04-06).
        var report=ItineraryConditionReport.evaluate(input,result);
        if(!report.complete()) {
            var unmet=report.checks().stream().filter(c->c.status()==ItineraryConditionReport.Status.UNMET).findFirst().orElseThrow();
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "조건을 충족하지 못한 항목이 있어 저장할 수 없어요. "+unmet.name()+": "+unmet.detail());
        }
        var destination=input.places().destination();
        var already=existing(userId,destination.placeId(),result.days().getFirst().date(),jobId);
        if(already!=null) return new Saved(already,false);
        var saved=itineraries.saveAndFlush(new Itinerary(userId,job.getRequestId(),destination.placeId(),
            defaultTitle(destination.name(),result.days().size()),result.days().getFirst().date(),result.days().getLast().date(),
            transportation(input),mapper.writeValueAsString(snapshot(jobId,input,result))));
        for(var day:result.days()) {
            int sequence=0;
            for(var item:day.items()) items.save(new ItineraryItem(saved.getId(),day.number(),++sequence,item.type(),item.placeId(),
                day.date().atTime(item.start()),day.date().atTime(item.end()),activity(item),travelMinutes(item),
                mapper.writeValueAsString(placeSnapshot(item))));
        }
        items.flush();
        return new Saved(saved.getId(),true);
    }

    @Transactional(readOnly=true,timeout=10)
    public List<SavedItinerary.Summary> list(String loginId) {
        return itineraries.findByUserIdOrderByStartDateDescIdDesc(member(loginId)).stream().map(this::summary).toList();
    }

    @Transactional(readOnly=true,timeout=10)
    public SavedItinerary.Detail detail(Long id, String loginId) {
        var itinerary=owned(id,loginId);
        var days=new ArrayList<SavedItinerary.Day>();
        for(var item:items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(id)) {
            if(days.isEmpty() || days.getLast().number()!=item.getDayNo())
                days.add(new SavedItinerary.Day(item.getDayNo(),item.getStartAt().toLocalDate(),new ArrayList<>()));
            days.getLast().items().add(view(item));
        }
        return new SavedItinerary.Detail(summary(itinerary),transportName(itinerary.getTransportation()),days);
    }

    @Transactional(timeout=10)
    public void rename(Long id, String loginId, String title) {
        String trimmed=title==null?"":title.strip();
        if(trimmed.isEmpty() || trimmed.length()>150) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
            "일정 이름은 1자 이상 150자 이하로 입력해 주세요.");
        owned(id,loginId).rename(trimmed);
        itineraries.flush();
    }

    @Transactional(timeout=10)
    public void delete(Long id, String loginId) {
        var itinerary=owned(id,loginId);
        // 세부 항목과 일정 기본정보를 같은 트랜잭션에서 제거한다.
        items.deleteByItineraryId(itinerary.getId());
        itineraries.delete(itinerary);
        itineraries.flush();
    }

    private Itinerary owned(Long id, String loginId) {
        return itineraries.findByIdAndUserId(id,member(loginId))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"저장한 일정을 찾을 수 없어요."));
    }
    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
    // 같은 초안을 다시 저장하면 새 일정을 만들지 않고 저장해 둔 일정을 보여 준다.
    private Long existing(Long userId, Long destinationPlaceId, LocalDate startDate, UUID jobId) {
        for(var itinerary:itineraries.findByUserIdAndDestinationPlaceIdAndStartDate(userId,destinationPlaceId,startDate))
            if(jobId.toString().equals(mapper.readTree(itinerary.getConditionsSnapshotJson()).path("jobId").asText("")))
                return itinerary.getId();
        return null;
    }
    private SavedItinerary.Summary summary(Itinerary itinerary) {
        var snapshot=mapper.readTree(itinerary.getConditionsSnapshotJson());
        return new SavedItinerary.Summary(itinerary.getId(),itinerary.getTitle(),
            snapshot.path("destination").path("name").asText(""),itinerary.getStartDate(),itinerary.getEndDate(),
            (int)(itinerary.getEndDate().toEpochDay()-itinerary.getStartDate().toEpochDay())+1,itinerary.getCreatedAt());
    }
    private SavedItinerary.Item view(ItineraryItem item) {
        var snapshot=mapper.readTree(item.getPlaceSnapshotJson()==null?"{}":item.getPlaceSnapshotJson());
        return new SavedItinerary.Item(item.getItemType(),typeName(item.getItemType()),
            item.getStartAt().toLocalTime(),item.getEndAt().toLocalTime(),
            snapshot.path("name").asText(item.getActivity()),snapshot.path("note").asText(""),
            snapshot.path("mapUrl").asText(""),snapshot.path("sourceUrl").asText(""),item.getTravelMinutes());
    }
    private Map<String,Object> snapshot(UUID jobId, Input input, Result result) {
        var destination=input.places().destination();
        return Map.of("schemaVersion",1,"jobId",jobId.toString(),
            "destination",Map.of("placeId",destination.placeId(),"name",destination.name(),"region",destination.region()),
            "schedule",Map.of("dayStart",input.start().toString(),"dayEnd",input.end().toString(),
                "transport",input.transport().name(),"visitMinutes",input.visitMinutes(),"dayCount",result.days().size()),
            "conditions",input.conditions());
    }
    private Map<String,Object> placeSnapshot(Item item) {
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("name",item.name()); snapshot.put("note",item.note());
        snapshot.put("mapUrl",item.mapUrl()); snapshot.put("sourceUrl",item.sourceUrl());
        if(item.checkedAt()!=null) snapshot.put("checkedAt",item.checkedAt().toString());
        return snapshot;
    }
    private String activity(Item item) {
        String activity=typeName(item.type())+" · "+item.name();
        return activity.length()>1000?activity.substring(0,1000):activity;
    }
    private Integer travelMinutes(Item item) {
        return item.travelSeconds()==null?null:(item.travelSeconds()+59)/60;
    }
    private String defaultTitle(String destination, int days) {
        String title=destination+" "+days+"일 일정";
        return title.length()>150?title.substring(0,150):title;
    }
    // DB의 이동수단 코드는 CAR·TRANSIT을 사용한다. 일정 초안은 두 가지만 만든다.
    private String transportation(Input input) {
        return input.transport()==KakaoMapClient.Transport.CAR?"CAR":"TRANSIT";
    }
    private String transportName(String transportation) {
        return "CAR".equals(transportation)?"자동차":"대중교통";
    }
    private String typeName(String type) {
        return switch(type) {
            case "VISIT" -> "관광";
            case "MEAL" -> "식사";
            case "STAY" -> "숙소";
            case "TRANSIT" -> "이동";
            default -> "자유 시간";
        };
    }
}
