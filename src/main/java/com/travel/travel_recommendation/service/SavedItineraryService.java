package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.dto.*;
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

/** 임시 일정 초안을 회원의 내 일정으로 저장하고 조회·제목 수정·삭제와 수정안 적용·되돌리기를 처리한다. */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class SavedItineraryService {
    private final RecommendationJobService access;
    private final ItineraryRepository itineraries;
    private final ItineraryItemRepository items;
    private final AiJobRepository jobs;
    private final UserRepository users;
    private final PlaceRepository places;
    private final PlaceCandidateService placeCandidates;
    private final ObjectMapper mapper;
    private final org.springframework.beans.factory.ObjectProvider<VirtualReservationService> reservations;
    /** 저장 결과. created=false이면 같은 초안을 이미 저장해 둔 일정이다. */
    public record Saved(Long id, boolean created) {}
    /** 수정 초안을 다시 만들 때 필요한 저장 정보. editableJobId가 있으면 원래 초안을 그대로 쓰면 된다. */
    public record EditSource(UUID editableJobId, ConfirmedTravelConditions conditions, DestinationCandidate destination) {}
    /** 수정할 초안. missingPlaces는 지금 확인된 관광지 후보에 없어 수정안에서 바꿔야 하는 관광지 이름이다. */
    public record EditDraft(UUID jobId, List<String> missingPlaces) {}

    @Transactional(timeout=10)
    public Saved save(UUID jobId, String loginId) {
        return save(jobId,loginId,null);
    }

    /** guestHash는 로그인 전에 같은 세션에서 비회원으로 만든 초안을 저장할 때 쓴다. */
    @Transactional(timeout=10)
    public Saved save(UUID jobId, String loginId, byte[] guestHash) {
        Long userId=member(loginId);
        var job=access.owned(jobId,loginId,guestHash,"ITINERARY");
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
            transportation(input),mapper.writeValueAsString(snapshot(jobId,input,result,report))));
        writeItems(saved.getId(),result);
        return new Saved(saved.getId(),true);
    }

    /**
     * 완성된 수정안을 저장한 일정에 덮어쓴다(SFR-08-04). 가장 최근에 적용한 일정에서 이어진 수정안만 받고,
     * 화면을 연 뒤 다른 곳에서 먼저 바뀐 일정은 덮어쓰지 않는다. 실패하면 기존 일정을 그대로 둔다.
     */
    @Transactional(timeout=10)
    public void apply(Long id, UUID jobId, String version, String loginId) {
        Long userId=member(loginId);
        var itinerary=itineraries.lockByIdAndUserId(id,userId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"저장한 일정을 찾을 수 없어요."));
        if(version==null || !version.equals(itinerary.getUpdatedAt().toString()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 이 일정을 먼저 바꿨어요. 내 일정에서 최신 내용을 확인한 뒤 다시 수정해 주세요.");
        var job=access.owned(jobId,loginId,null,"ITINERARY");
        if(!"COMPLETED".equals(job.getStatus()) || job.getResultJson()==null)
            throw new ResponseStatusException(HttpStatus.CONFLICT,"아직 완성되지 않은 수정안이에요. 수정안이 준비된 뒤 적용해 주세요.");
        String current=snapshotJobId(itinerary);
        var lineage=lineage(jobId,userId);
        if(current.equals(jobId.toString()) || !lineage.contains(current))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"가장 최근에 적용한 일정에서 만든 수정안만 적용할 수 있어요. 내 일정에서 다시 수정해 주세요.");
        var input=mapper.readValue(job.getRequestJson(),Input.class);
        var result=mapper.readValue(job.getResultJson(),Result.class);
        if(!itinerary.getDestinationPlaceId().equals(input.places().destination().placeId())
                || input.conditions().startDate()==null || result.days().stream().anyMatch(day->day.date()==null))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"이 일정에 적용할 수 없는 수정안이에요.");
        var report=ItineraryConditionReport.evaluate(input,result);
        if(!report.complete()) {
            var unmet=report.checks().stream().filter(c->c.status()==ItineraryConditionReport.Status.UNMET).findFirst().orElseThrow();
            throw new ResponseStatusException(HttpStatus.CONFLICT,"조건을 충족하지 못한 항목이 있어 적용할 수 없어요. "+unmet.name()+": "+unmet.detail());
        }
        // 덮어쓰기 전 일정을 조건 스냅샷의 previous에 한 단계만 남겨 되돌릴 수 있게 한다(테이블 변경 없음).
        var previous=savedVersion(itinerary,items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(itinerary.getId()),"APPLY");
        items.deleteByItineraryId(itinerary.getId());
        items.flush();
        var snapshot=snapshot(jobId,input,result,report);
        snapshot.put("previous",previous);
        // 저장한 일정에서 다시 만든 초안은 추천 이력과 연결되지 않으므로 기존 이력 연결을 유지한다.
        itinerary.applyRevision(job.getRequestId()!=null?job.getRequestId():itinerary.getRequestId(),result.days().getFirst().date(),result.days().getLast().date(),
            transportation(input),mapper.writeValueAsString(snapshot));
        itineraries.flush();
        writeItems(itinerary.getId(),result);
    }

    /**
     * 가장 최근의 수정안 적용을 되돌린다(SFR-08-04). previous에 남긴 직전 일정과 지금 일정을 맞바꾸므로,
     * 한 번 더 누르면 되돌리기를 취소한다. 화면을 연 뒤 다른 곳에서 먼저 바뀐 일정은 바꾸지 않는다.
     * 돌려주는 값은 수정 전으로 되돌렸으면 true, 되돌리기를 취소했으면 false다.
     */
    @Transactional(timeout=10)
    public boolean undo(Long id, String version, String loginId) {
        var itinerary=itineraries.lockByIdAndUserId(id,member(loginId))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"저장한 일정을 찾을 수 없어요."));
        if(version==null || !version.equals(itinerary.getUpdatedAt().toString()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 이 일정을 먼저 바꿨어요. 내 일정에서 최신 내용을 확인한 뒤 다시 시도해 주세요.");
        var previous=mapper.readTree(itinerary.getConditionsSnapshotJson()).path("previous");
        if(!previous.isObject() || !previous.path("snapshot").isObject())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"되돌릴 수정 내역이 없어요.");
        boolean undoing=!"UNDO".equals(previous.path("replacedBy").asText(""));
        // 지금 일정을 새 previous로 남기고, 직전 일정의 스냅샷·항목을 다시 쓴다.
        var current=savedVersion(itinerary,items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(id),undoing?"UNDO":"APPLY");
        var restoredItems=previousItems(id,previous);
        var restored=(tools.jackson.databind.node.ObjectNode)previous.path("snapshot").deepCopy();
        restored.set("previous",mapper.valueToTree(current));
        items.deleteByItineraryId(id);
        items.flush();
        // 추천 이력 연결은 바꾸지 않는다. 그 사이 이력을 지웠으면 끊긴 연결을 되살리지 않기 위해서다.
        itinerary.applyRevision(itinerary.getRequestId(),LocalDate.parse(previous.path("startDate").asText()),
            LocalDate.parse(previous.path("endDate").asText()),previous.path("transportation").asText(),mapper.writeValueAsString(restored));
        itineraries.flush();
        items.saveAll(restoredItems);
        items.flush();
        return undoing;
    }
    // 지금 일정 한 벌(조건 스냅샷·기간·이동수단·항목)을 되돌리기용으로 남긴다. 스냅샷 안의 previous는 빼서 한 단계만 남긴다.
    private Map<String,Object> savedVersion(Itinerary itinerary, List<ItineraryItem> savedItems, String replacedBy) {
        var snapshot=(tools.jackson.databind.node.ObjectNode)mapper.readTree(itinerary.getConditionsSnapshotJson());
        snapshot.remove("previous");
        var version=new LinkedHashMap<String,Object>();
        version.put("replacedBy",replacedBy);
        version.put("replacedAt",KoreaTime.now().toString());
        version.put("startDate",itinerary.getStartDate().toString());
        version.put("endDate",itinerary.getEndDate().toString());
        version.put("transportation",itinerary.getTransportation());
        version.put("snapshot",snapshot);
        var savedRows=new ArrayList<Map<String,Object>>();
        for(var item:savedItems) {
            var row=new LinkedHashMap<String,Object>();
            row.put("dayNo",item.getDayNo()); row.put("sequenceNo",item.getSequenceNo());
            row.put("itemType",item.getItemType()); row.put("placeId",item.getPlaceId());
            row.put("startAt",item.getStartAt().toString()); row.put("endAt",item.getEndAt().toString());
            row.put("activity",item.getActivity()); row.put("travelMinutes",item.getTravelMinutes());
            row.put("placeSnapshotJson",item.getPlaceSnapshotJson());
            savedRows.add(row);
        }
        version.put("items",savedRows);
        return version;
    }
    // previous에 남긴 항목을 저장 항목으로 되살린다. 화면 비교와 되돌리기에 같이 쓴다.
    private List<ItineraryItem> previousItems(Long itineraryId, tools.jackson.databind.JsonNode previous) {
        var restored=new ArrayList<ItineraryItem>();
        for(var row:previous.path("items")) restored.add(new ItineraryItem(itineraryId,row.path("dayNo").asInt(),row.path("sequenceNo").asInt(),
            row.path("itemType").asText(),row.path("placeId").isNumber()?row.path("placeId").asLong():null,
            LocalDateTime.parse(row.path("startAt").asText()),LocalDateTime.parse(row.path("endAt").asText()),row.path("activity").asText(),
            row.path("travelMinutes").isNumber()?row.path("travelMinutes").asInt():null,
            row.path("placeSnapshotJson").isTextual()?row.path("placeSnapshotJson").asText():null));
        return restored;
    }

    /** 이 수정안을 덮어쓸 수 있는 저장 일정 목록. 수정안이 아니면 비어 있다. */
    @Transactional(readOnly=true,timeout=10)
    public List<SavedItinerary.Target> targets(UUID jobId, String loginId) {
        if(loginId==null) return List.of();
        Long userId=member(loginId);
        var lineage=lineage(jobId,userId);
        if(lineage.size()<2) return List.of();
        return itineraries.findByUserIdOrderByStartDateDescIdDesc(userId).stream()
            .filter(i->{ String source=snapshotJobId(i); return !source.equals(jobId.toString()) && lineage.contains(source); })
            .map(i->new SavedItinerary.Target(i.getId(),i.getTitle(),i.getUpdatedAt().toString())).toList();
    }

    /** 이 초안을 그대로 저장해 둔 내 일정 목록. 이미 저장한 초안에 '저장하기' 대신 '저장한 일정 보기'를 보여 줄 때 쓴다. */
    @Transactional(readOnly=true,timeout=10)
    public List<SavedItinerary.Target> savedFrom(UUID jobId, String loginId) {
        if(loginId==null) return List.of();
        Long userId=member(loginId);
        return itineraries.findByUserIdOrderByStartDateDescIdDesc(userId).stream()
            .filter(i->snapshotJobId(i).equals(jobId.toString()))
            .map(i->new SavedItinerary.Target(i.getId(),i.getTitle(),i.getUpdatedAt().toString())).toList();
    }

    // 수정안이 어떤 초안에서 이어졌는지 원본 방향으로 따라간다. 같은 회원의 작업만 보고 최대 20단계까지 확인한다.
    private List<String> lineage(UUID jobId, Long userId) {
        var ids=new ArrayList<String>(); UUID next=jobId;
        while(next!=null && ids.size()<20) {
            var job=jobs.findById(next).filter(j->userId.equals(j.getUserId()) && "ITINERARY".equals(j.getJobType())).orElse(null);
            if(job==null || ids.contains(next.toString())) break;
            ids.add(next.toString());
            var revision=mapper.readValue(job.getRequestJson(),Input.class).revision();
            next=revision==null?null:revision.sourceJobId();
        }
        return ids;
    }
    private String snapshotJobId(Itinerary itinerary) {
        return mapper.readTree(itinerary.getConditionsSnapshotJson()).path("jobId").asText("");
    }
    private void writeItems(Long itineraryId, Result result) {
        for(var day:result.days()) {
            int sequence=0;
            for(var item:day.items()) items.save(new ItineraryItem(itineraryId,day.number(),++sequence,item.type(),item.placeId(),
                day.date().atTime(item.start()),day.date().atTime(item.end()),activity(item),travelMinutes(item),
                mapper.writeValueAsString(placeSnapshot(item))));
        }
        items.flush();
    }

    @Transactional(readOnly=true,timeout=10)
    public List<SavedItinerary.Summary> list(String loginId) {
        return itineraries.findByUserIdOrderByStartDateDescIdDesc(member(loginId)).stream().map(this::summary).toList();
    }

    /** 목록 카드에 더 보여 줄 정보: 이동수단 이름과, 대표 사진 후보가 되는 관광지 ID(방문 순서). */
    public record ListExtra(String transportName, List<Long> visitPlaceIds) {}

    @Transactional(readOnly=true,timeout=10)
    public Map<Long,ListExtra> listExtras(String loginId) {
        var extras=new HashMap<Long,ListExtra>();
        for(var itinerary:itineraries.findByUserIdOrderByStartDateDescIdDesc(member(loginId))) {
            var visitIds=items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(itinerary.getId()).stream()
                .filter(item->"VISIT".equals(item.getItemType()) && item.getPlaceId()!=null).map(ItineraryItem::getPlaceId).toList();
            extras.put(itinerary.getId(),new ListExtra(transportName(itinerary.getTransportation()),visitIds));
        }
        return extras;
    }

    @Transactional(readOnly=true,timeout=10)
    public SavedItinerary.Detail detail(Long id, String loginId) {
        var itinerary=owned(id,loginId);
        var days=days(items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(id));
        var snapshot=mapper.readTree(itinerary.getConditionsSnapshotJson());
        // 저장 당시 확인한 조건 결과와 참고 가격. 예전 형식의 일정에는 없어서 비어 있다.
        var checks=new ArrayList<SavedItinerary.Check>();
        for(var check:snapshot.path("conditionChecks")) {
            var status=ItineraryConditionReport.Status.valueOf(check.path("status").asText());
            checks.add(new SavedItinerary.Check(check.path("name").asText(),status.name(),status.getLabel(),check.path("detail").asText("")));
        }
        var prices=snapshot.path("prices").isObject()?mapper.treeToValue(snapshot.path("prices"),Prices.class):null;
        return new SavedItinerary.Detail(summary(itinerary),transportName(itinerary.getTransportation()),days,
            editable(itinerary,loginId),itinerary.getUpdatedAt().toString(),List.copyOf(checks),
            prices==null?List.of():prices.quotes(),prices==null?null:koreanTime(prices.checkedAt()),lastChange(itinerary,snapshot,days));
    }
    private List<SavedItinerary.Day> days(List<ItineraryItem> savedItems) {
        var days=new ArrayList<SavedItinerary.Day>();
        for(var item:savedItems) {
            if(days.isEmpty() || days.getLast().number()!=item.getDayNo())
                days.add(new SavedItinerary.Day(item.getDayNo(),item.getStartAt().toLocalDate(),new ArrayList<>()));
            days.getLast().items().add(view(item));
        }
        return days;
    }
    // 직전 일정(previous)이 있으면 지금 일정과 비교한다. 수정안을 적용한 적 없는 일정은 null이다.
    private SavedItinerary.LastChange lastChange(Itinerary itinerary, tools.jackson.databind.JsonNode snapshot, List<SavedItinerary.Day> days) {
        var previous=snapshot.path("previous");
        if(!previous.isObject() || !previous.path("snapshot").isObject()) return null;
        var before=days(previousItems(itinerary.getId(),previous));
        var settings=new ArrayList<String>();
        ItineraryChanges.setting(settings,"여행 날짜",previous.path("startDate").asText()+" ~ "+previous.path("endDate").asText(),
            itinerary.getStartDate()+" ~ "+itinerary.getEndDate());
        ItineraryChanges.setting(settings,"이동수단",transportName(previous.path("transportation").asText()),transportName(itinerary.getTransportation()));
        var beforeSchedule=previous.path("snapshot").path("schedule"); var afterSchedule=snapshot.path("schedule");
        ItineraryChanges.setting(settings,"하루 일정 시간",dayHours(beforeSchedule),dayHours(afterSchedule));
        ItineraryChanges.setting(settings,"관광지 기본 체류시간",visitMinutes(beforeSchedule),visitMinutes(afterSchedule));
        ItineraryChanges.setting(settings,"집 출발·귀가 시각",homeHours(previous.path("snapshot").path("home")),homeHours(snapshot.path("home")));
        var comparison=ItineraryChanges.compare(ItineraryChanges.fromSaved(before),ItineraryChanges.fromSaved(days),settings);
        var changedAt=previous.path("replacedAt").isTextual()
            ?previous.path("replacedAt").asText().replace('T',' ').substring(0,16):null;
        return new SavedItinerary.LastChange("UNDO".equals(previous.path("replacedBy").asText("")),changedAt,comparison);
    }
    private static String dayHours(tools.jackson.databind.JsonNode schedule) {
        return schedule.path("dayStart").isTextual() && schedule.path("dayEnd").isTextual()
            ?schedule.path("dayStart").asText()+"~"+schedule.path("dayEnd").asText():null;
    }
    private static String homeHours(tools.jackson.databind.JsonNode home) {
        return home.path("departAt").isTextual() && home.path("arriveBy").isTextual()
            ?home.path("departAt").asText()+" 출발 · "+home.path("arriveBy").asText()+" 귀가":null;
    }
    private static String visitMinutes(tools.jackson.databind.JsonNode schedule) {
        return schedule.path("visitMinutes").isNumber()?schedule.path("visitMinutes").asInt()+"분":null;
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
        // 가상 예약은 남기고 일정 연결만 끊은 뒤(정의서 '삭제와 참조 유지'), 세부 항목과 일정 기본정보를 같은 트랜잭션에서 제거한다.
        var reservation=reservations.getIfAvailable();
        if(reservation!=null) reservation.detachItinerary(itinerary.getId());
        items.deleteByItineraryId(itinerary.getId());
        itineraries.delete(itinerary);
        itineraries.flush();
    }

    /** 수정 초안을 다시 만들기 전에 저장한 조건과 여행지를 읽는다. 원래 초안을 쓸 수 있으면 그 ID만 돌려준다. */
    @Transactional(readOnly=true,timeout=10)
    public EditSource editSource(Long id, String loginId) {
        var itinerary=owned(id,loginId);
        var editable=editable(itinerary,loginId);
        if(editable!=null) return new EditSource(editable,null,null);
        var snapshot=mapper.readTree(itinerary.getConditionsSnapshotJson());
        // 여행지는 저장 당시 이름이 아니라 지금 장소 정보로 확인한다.
        var destination=places.findById(itinerary.getDestinationPlaceId())
            .filter(p->p.isActive() && "DESTINATION".equals(p.getPlaceType()))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.CONFLICT,"이 일정의 여행지 정보가 바뀌어 수정할 수 없어요. 여행 조건부터 새 일정을 만들어 주세요."));
        return new EditSource(null,savedConditions(snapshot),new DestinationCandidate(destination.getId(),destination.getName(),
            destination.getRegion(),destination.getDescription(),destination.getSourceUrl(),destination.getVerifiedAt()));
    }

    /**
     * 저장한 일정의 관광지 중 지금 확인된 장소가 아닌 곳의 이름. 카카오에서 찾은 장소는 하루가 지나면 확인이 끝난다.
     * 수정 초안을 다시 만들기 전에 이 이름들로 다시 찾아 확인한다.
     */
    @Transactional(readOnly=true,timeout=10)
    public List<String> unverifiedAttractions(Long id, String loginId, String region) {
        var itinerary=owned(id,loginId);
        return items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(itinerary.getId()).stream()
            .filter(item->"VISIT".equals(item.getItemType()) && item.getPlaceId()!=null)
            .map(item->item.getPlaceId()).distinct()
            .filter(placeId->placeCandidates.usableAttraction(placeId,region).isEmpty())
            .flatMap(placeId->places.findById(placeId).filter(place->place.isActive()).stream())
            .map(place->place.getName()).toList();
    }

    /**
     * 원래 초안이 만료·삭제된 저장 일정을, 저장한 조건·항목과 지금 확인된 장소 후보로 수정용 초안으로 다시 만든다.
     * 시각·장소는 저장한 그대로 옮기며, 이 일정이 새 초안을 가리키게 해 이후 수정안 적용 흐름을 그대로 쓴다.
     */
    @Transactional(timeout=10)
    public EditDraft restoreDraft(Long id, UUID parentId, String loginId) {
        var itinerary=itineraries.lockByIdAndUserId(id,member(loginId))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"저장한 일정을 찾을 수 없어요."));
        // 다른 화면에서 먼저 다시 만들었으면 그 초안을 쓴다.
        var editable=editable(itinerary,loginId);
        if(editable!=null) return new EditDraft(editable,List.of());
        var snapshot=mapper.readTree(itinerary.getConditionsSnapshotJson());
        var schedule=snapshot.path("schedule");
        if(!schedule.path("dayStart").isTextual() || !schedule.path("dayEnd").isTextual() || !schedule.path("transport").isTextual())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"예전 형식으로 저장한 일정이라 수정할 수 없어요. 여행 조건부터 새 일정을 만들어 주세요.");
        var conditions=savedConditions(snapshot);
        var pool=placeCandidates.findForSchedule(parentId,itinerary.getDestinationPlaceId(),loginId,null,conditions);
        var savedItems=items.findByItineraryIdOrderByDayNoAscSequenceNoAsc(id);
        // 방문한 관광지는 지금 확인된 후보에서 찾는다. 후보에 없는 곳은 장소 정보로 넣되 수정안에서 바꾸도록 안내한다.
        var attractions=new ArrayList<PlaceCandidate>(); var missing=new ArrayList<String>();
        // 숙소 도착 뒤의 방문은 저녁 관광이라 낮 관광지로 되돌리지 않고 저녁 관광 후보 맨 앞에 둔다.
        // 저녁 관광 후보에 없으면 다시 만든 초안이 그곳을 '후보에 없는 장소'로 보고 미충족으로 표시한다.
        Set<String> afterStay=new HashSet<>(); var savedEvening=new ArrayList<PlaceCandidate>();
        for(var item:savedItems) {
            if("STAY".equals(item.getItemType())) afterStay.add(String.valueOf(item.getDayNo()));
            if(!"VISIT".equals(item.getItemType()) || item.getPlaceId()==null
                || attractions.stream().anyMatch(p->p.placeId().equals(item.getPlaceId()))
                || savedEvening.stream().anyMatch(p->p.placeId().equals(item.getPlaceId()))) continue;
            var target=afterStay.contains(String.valueOf(item.getDayNo()))?savedEvening:attractions;
            // 후보 수 제한에 밀려 목록에 없을 뿐 지금도 확인된 장소면 확인된 후보로 본다.
            var current=pool.attractions().stream().filter(p->p.placeId().equals(item.getPlaceId())).findFirst()
                .or(()->placeCandidates.usableAttraction(item.getPlaceId(),pool.destination().region()));
            if(current.isPresent()) { target.add(current.get()); continue; }
            var place=places.findById(item.getPlaceId()).orElseThrow(()->
                new ResponseStatusException(HttpStatus.CONFLICT,"저장한 관광지를 찾을 수 없어 수정할 수 없어요."));
            target.add(placeCandidates.candidate(place)); missing.add(place.getName());
        }
        var evening=new ArrayList<>(savedEvening);
        if(pool.accommodationNeeded()) TripConstraints.eveningPlaces(TripConstraints.nearCenterFirst(pool.attractions()),
                attractions.stream().map(PlaceCandidate::placeId).toList(),conditions.durationDays()==null?0:conditions.durationDays()+1)
            .stream().filter(p->!evening.contains(p)).forEach(evening::add);
        // 식당·숙소도 지금 후보에 없으면(예: 동선 주변에서 따로 찾은 음식점) 저장한 장소를 후보에 더한다.
        // 더하지 않으면 다시 만든 초안이 그 장소를 '후보에 없는 장소'로 보고 미충족으로 표시한다.
        var restaurants=withSavedPlaces(pool.restaurants(),savedItems,"MEAL");
        var accommodations=withSavedPlaces(pool.accommodations(),savedItems,"STAY");
        var candidates=new PlaceCandidateView(parentId,pool.destination(),pool.durationDays(),pool.accommodationNeeded(),
            List.copyOf(attractions),accommodations,restaurants);
        // 필수 방문 장소·하루 방문 수 같은 사용자가 정한 조건도 되살려, 다시 만든 초안의 수정안과 조건표가 같은 기준을 쓰게 한다.
        var input=new Input(parentId,candidates,conditions,LocalTime.parse(schedule.path("dayStart").asText()),
            LocalTime.parse(schedule.path("dayEnd").asText()),KakaoMapClient.Transport.valueOf(schedule.path("transport").asText()),
            schedule.path("visitMinutes").asInt(90),null,savedConstraints(snapshot.path("constraints")))
            .withEvening(List.copyOf(evening))
            .withHome(savedHome(snapshot.path("home")))
            // 저장한 숙소를 고른 숙소로 이어받아, 시간만 바꾼 수정안에서 숙소가 바뀌지 않게 한다('숙소 바꾸기'를 고르면 그 선택이 우선한다).
            .withChoices(new Choices(List.of(),List.of(),savedItems.stream().filter(item->"STAY".equals(item.getItemType()) && item.getPlaceId()!=null)
                .map(ItineraryItem::getPlaceId).findFirst().orElse(null)));
        var result=restoredResult(pool.destination().name(),input.transport(),savedItems);
        var parent=access.owned(parentId,loginId,null,"RECOMMENDATION");
        var job=jobs.saveAndFlush(AiJob.restoredItinerary(parent,mapper.writeValueAsString(input),mapper.writeValueAsString(result)));
        // 내용이 같은 초안으로 연결만 바꾼다. 저장한 일정의 시각·장소는 그대로다.
        var relinked=(tools.jackson.databind.node.ObjectNode)snapshot;
        relinked.put("jobId",job.getId().toString());
        itinerary.relinkDraft(mapper.writeValueAsString(relinked));
        itineraries.flush();
        return new EditDraft(job.getId(),List.copyOf(missing));
    }
    /** 저장한 일정의 해당 종류(식사·숙박) 장소 중 지금 후보에 없는 곳을 장소 정보로 더한 목록. */
    private List<PlaceCandidate> withSavedPlaces(List<PlaceCandidate> current, List<ItineraryItem> savedItems, String itemType) {
        var result=new ArrayList<>(current);
        for(var item:savedItems) {
            if(!itemType.equals(item.getItemType()) || item.getPlaceId()==null
                || result.stream().anyMatch(p->p.placeId().equals(item.getPlaceId()))) continue;
            places.findById(item.getPlaceId()).ifPresent(place->result.add(placeCandidates.candidate(place)));
        }
        return List.copyOf(result);
    }
    /** 저장할 때 남긴 사용자 조건(필수 방문 장소·하루 방문 수·예산 기준 등). 조건 없이 만들었거나 예전에 저장한 일정에는 없다. */
    private Constraints savedConstraints(tools.jackson.databind.JsonNode constraints) {
        return constraints.isObject()?readSaved(constraints,Constraints.class):null;
    }
    /** 저장할 때 남긴 집 출발·귀가 설정. 출발지는 시·구 이름만 있고 좌표는 없다. 예전에 저장한 일정에는 없다. */
    private static HomeTrip savedHome(tools.jackson.databind.JsonNode home) {
        if(!home.path("departAt").isTextual() || !home.path("arriveBy").isTextual() || !home.path("minutes").canConvertToInt()) return null;
        return new HomeTrip(home.path("origin").asText("집"),LocalTime.parse(home.path("departAt").asText()),
            LocalTime.parse(home.path("arriveBy").asText()),home.path("minutes").asInt(),home.path("basis").asText("저장한 일정의 이동시간"),null,
            home.path("returnPlace").isTextual()?home.path("returnPlace").asText():null,
            home.path("returnMinutes").canConvertToInt()?home.path("returnMinutes").asInt():null,
            home.path("returnBasis").isTextual()?home.path("returnBasis").asText():null);
    }
    private ConfirmedTravelConditions savedConditions(tools.jackson.databind.JsonNode snapshot) {
        if(!snapshot.path("conditions").isObject())
            throw new ResponseStatusException(HttpStatus.CONFLICT,"예전 형식으로 저장한 일정이라 수정할 수 없어요. 여행 조건부터 새 일정을 만들어 주세요.");
        return readSaved(snapshot.path("conditions"),ConfirmedTravelConditions.class);
    }
    /**
     * 저장한 일정의 조건은 오래 보관하므로, 저장한 뒤에 늘어난 항목(예: purposeSuggested)이 없을 수 있다.
     * 없는 true/false·숫자 항목은 false·0으로 읽어 예전에 저장한 일정도 수정할 수 있게 한다.
     */
    private <T> T readSaved(tools.jackson.databind.JsonNode node, Class<T> type) {
        return mapper.readerFor(type).without(tools.jackson.databind.DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES).readValue(node);
    }
    // 저장한 항목을 일정 초안 결과 형식으로 되돌린다. AI 안내·주변 지역·참고 가격은 다시 만들지 않는다.
    private Result restoredResult(String destination, KakaoMapClient.Transport transport, List<ItineraryItem> savedItems) {
        var days=new ArrayList<Day>(); Long stayPlaceId=null;
        for(var item:savedItems) {
            if(days.isEmpty() || days.getLast().number()!=item.getDayNo())
                days.add(new Day(item.getDayNo(),item.getStartAt().toLocalDate(),new ArrayList<>()));
            var snapshot=mapper.readTree(item.getPlaceSnapshotJson()==null?"{}":item.getPlaceSnapshotJson());
            var checkedAt=snapshot.path("checkedAt").isTextual()?Instant.parse(snapshot.path("checkedAt").asText()):null;
            days.getLast().items().add(new Item(item.getItemType(),item.getPlaceId(),snapshot.path("name").asText(item.getActivity()),
                item.getStartAt().toLocalTime(),item.getEndAt().toLocalTime(),text(snapshot,"note"),text(snapshot,"mapUrl"),
                text(snapshot,"sourceUrl"),item.getTravelMinutes()==null?null:item.getTravelMinutes()*60,checkedAt));
            if("STAY".equals(item.getItemType()) && stayPlaceId==null) stayPlaceId=item.getPlaceId();
        }
        var lodging=stayPlaceId==null?null:new Lodging(stayPlaceId,"저장한 일정에 넣은 숙소예요.",List.of());
        return new Result(destination,days,transport,Instant.now(),null,null,lodging,List.of(),null);
    }
    private static String text(tools.jackson.databind.JsonNode node, String field) {
        return node.path(field).isTextual()?node.path(field).asText():null;
    }

    // 원래 초안이 만료 전이고 본인 것일 때만 그 초안에서 수정안을 만들 수 있다.
    private UUID editable(Itinerary itinerary, String loginId) {
        // 원래 초안이 만료됐거나 비회원일 때 만든 초안이면 이어서 수정할 수 없다. 예외 없이 확인해 상세 화면의 트랜잭션을 깨지 않는다.
        UUID id;
        try { id=UUID.fromString(snapshotJobId(itinerary)); }
        catch(RuntimeException noJobId) { return null; }
        return access.findOwned(id,loginId,null,"ITINERARY").filter(job->"COMPLETED".equals(job.getStatus())).map(job->id).orElse(null);
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
        // 숙소 근처 저녁 관광은 일정 초안 화면처럼 '저녁 관광'으로 보여 준다.
        String typeName="VISIT".equals(item.getItemType()) && snapshot.path("note").asText("").startsWith("저녁 관광")?"저녁 관광":typeName(item.getItemType());
        return new SavedItinerary.Item(item.getItemType(),typeName,
            item.getStartAt().toLocalTime(),item.getEndAt().toLocalTime(),
            snapshot.path("name").asText(item.getActivity()),snapshot.path("note").asText(""),
            snapshot.path("mapUrl").asText(""),snapshot.path("sourceUrl").asText(""),item.getTravelMinutes(),item.getPlaceId());
    }
    // schemaVersion 2부터 저장 당시 조건 확인 결과(conditionChecks)와 조회한 참고 가격(prices)을 함께 남긴다.
    private Map<String,Object> snapshot(UUID jobId, Input input, Result result, ItineraryConditionReport.Report report) {
        var destination=input.places().destination();
        var snapshot=new LinkedHashMap<String,Object>();
        snapshot.put("schemaVersion",2); snapshot.put("jobId",jobId.toString());
        snapshot.put("destination",Map.of("placeId",destination.placeId(),"name",destination.name(),"region",destination.region()));
        snapshot.put("schedule",Map.of("dayStart",input.start().toString(),"dayEnd",input.end().toString(),
            "transport",input.transport().name(),"visitMinutes",input.visitMinutes(),"dayCount",result.days().size()));
        // 집 출발·귀가 설정은 수정할 때 같은 기준으로 다시 짜도록 남긴다. 출발지는 시·구 이름만 있다.
        var home=input.home();
        if(home!=null) snapshot.put("home",Map.of("origin",home.origin(),"departAt",home.departAt().toString(),
            "arriveBy",home.arriveBy().toString(),"minutes",home.minutes(),"basis",home.basis(),
            "returnPlace",home.returnPlace(),"returnMinutes",home.returnMinutes(),"returnBasis",home.returnBasis()));
        snapshot.put("conditions",input.conditions());
        // 사용자가 정한 조건은 원래 초안이 없어져도 수정할 때 이어서 쓰도록 남긴다.
        if(input.constraints()!=null) snapshot.put("constraints",input.constraints());
        snapshot.put("conditionChecks",report.checks().stream()
            .map(c->Map.of("name",c.name(),"status",c.status().name(),"detail",c.detail()==null?"":c.detail())).toList());
        if(result.prices()!=null) snapshot.put("prices",result.prices());
        return snapshot;
    }
    private static String koreanTime(Instant time) {
        return time==null?null:java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").format(time.atZone(ZoneId.of("Asia/Seoul")));
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
