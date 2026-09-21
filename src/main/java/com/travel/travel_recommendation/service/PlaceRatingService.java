package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.PlaceRatingView.*;
import com.travel.travel_recommendation.entity.*;
import com.travel.travel_recommendation.repository.*;
import java.util.*;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * 장소 평가 등록·요약·갱신·삭제(SFR-10-01~04). 별점 1~5점과 사전 정의 태그 최대 3개만 받으며,
 * 회원·장소당 한 건이라 다시 평가하면 기존 평가를 갱신한다. 요약에는 평가자 정보를 넣지 않는다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceRatingService {
    public static final int MAX_TAGS = 3;
    private static final Map<String,String> RATABLE = Map.of("ATTRACTION","관광지","RESTAURANT","음식점","ACCOMMODATION","숙소");
    private final PlaceRatingRepository ratings;
    private final PlaceRatingTagRepository ratingTags;
    private final RatingTagRepository tags;
    private final PlaceRepository places;
    private final UserRepository users;

    @Transactional(readOnly=true,timeout=10)
    public Form form(Long placeId, String loginId) {
        Long userId=member(loginId);
        var place=ratable(placeId);
        var mine=ratings.findByUserIdAndPlaceId(userId,placeId).map(r->new Mine(r.getScore(),
            ratingTags.findByRatingIdOrderBySlot(r.getId()).stream().map(PlaceRatingTag::getTagId).toList(),r.getUpdatedAt())).orElse(null);
        return new Form(info(place),mine,activeTags(),summaries(List.of(placeId)).getOrDefault(placeId,Summary.EMPTY));
    }

    /** 새 평가를 등록하거나, 이미 평가한 장소면 별점과 태그를 새 선택값으로 바꾼다. created=true면 새 평가다. */
    @Transactional(timeout=10)
    public boolean save(Long placeId, String loginId, Integer score, List<Long> tagIds) {
        Long userId=member(loginId);
        ratable(placeId);
        if(score==null || score<1 || score>5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"별점을 1~5점 중에서 골라 주세요.");
        var chosen=tagIds==null?List.<Long>of():tagIds;
        if(new HashSet<>(chosen).size()!=chosen.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"같은 태그를 두 번 고를 수 없어요.");
        if(chosen.size()>MAX_TAGS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"태그는 최대 "+MAX_TAGS+"개까지 고를 수 있어요.");
        var allowed=activeTags().stream().map(Tag::id).collect(Collectors.toSet());
        if(!allowed.containsAll(chosen)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"화면에 있는 태그에서만 골라 주세요.");
        var existing=ratings.lockByUserIdAndPlaceId(userId,placeId).orElse(null);
        PlaceRating rating;
        boolean created=existing==null;
        try {
            if(created) rating=ratings.saveAndFlush(new PlaceRating(userId,placeId,score));
            else { existing.rescore(score); rating=existing; ratingTags.deleteByRating(rating.getId()); }
        } catch(DataIntegrityViolationException duplicate) {
            // 같은 회원이 다른 창에서 동시에 처음 평가한 경우다. 기존 평가를 덮어쓰지 않고 다시 시도하게 한다.
            throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 방금 이 장소를 평가했어요. 다시 열어 확인해 주세요.");
        }
        int slot=0;
        for(Long tagId:chosen) ratingTags.save(new PlaceRatingTag(rating.getId(),tagId,++slot));
        ratings.flush(); ratingTags.flush();
        return created;
    }

    /** 본인 평가만 지운다. 평가가 없으면 404로 알린다. */
    @Transactional(timeout=10)
    public void delete(Long placeId, String loginId) {
        Long userId=member(loginId);
        var rating=ratings.lockByUserIdAndPlaceId(userId,placeId)
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"삭제할 내 평가가 없어요."));
        ratingTags.deleteByRating(rating.getId());
        ratings.delete(rating);
        ratings.flush();
    }

    @Transactional(readOnly=true,timeout=10)
    public List<MyRating> mine(String loginId) {
        Long userId=member(loginId);
        var list=ratings.findByUserIdOrderByUpdatedAtDescIdDesc(userId);
        if(list.isEmpty()) return List.of();
        var names=tags.findAll().stream().collect(Collectors.toMap(RatingTag::getId,RatingTag::getName));
        var byRating=ratingTags.findByRatingIdIn(list.stream().map(PlaceRating::getId).toList()).stream()
            .sorted(Comparator.comparingInt(PlaceRatingTag::getSlot)).collect(Collectors.groupingBy(PlaceRatingTag::getRatingId));
        var placeById=places.findAllById(list.stream().map(PlaceRating::getPlaceId).toList()).stream()
            .collect(Collectors.toMap(Place::getId,p->p));
        return list.stream().filter(r->placeById.containsKey(r.getPlaceId())).map(r->new MyRating(info(placeById.get(r.getPlaceId())),r.getScore(),
            byRating.getOrDefault(r.getId(),List.of()).stream().map(t->names.get(t.getTagId())).toList(),r.getUpdatedAt())).toList();
    }

    /** 장소별 평균 별점·평가 수·많이 고른 태그 3개. 비회원도 볼 수 있다(SFR-10-02). */
    @Transactional(readOnly=true,timeout=10)
    public Map<Long,Summary> summaries(Collection<Long> placeIds) {
        if(placeIds==null || placeIds.isEmpty()) return Map.of();
        var ids=List.copyOf(new LinkedHashSet<>(placeIds));
        var names=tags.findAll().stream().collect(Collectors.toMap(RatingTag::getId,RatingTag::getName));
        Map<Long,List<TagCount>> tagCounts=new HashMap<>();
        for(var row:ratingTags.countTags(ids))
            tagCounts.computeIfAbsent((Long)row[0],k->new ArrayList<>()).add(new TagCount(names.get((Long)row[1]),((Number)row[2]).longValue()));
        var result=new HashMap<Long,Summary>();
        for(var row:ratings.summarize(ids)) {
            Long placeId=(Long)row[0];
            double average=Math.round(((Number)row[1]).doubleValue()*10)/10.0;
            var top=tagCounts.getOrDefault(placeId,List.of()).stream()
                .sorted(Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::name)).limit(3).toList();
            result.put(placeId,new Summary(average,((Number)row[2]).longValue(),top));
        }
        return result;
    }

    private List<Tag> activeTags() {
        return tags.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(t->new Tag(t.getId(),t.getName())).toList();
    }
    private Place ratable(Long placeId) {
        return places.findById(placeId).filter(p->p.isActive() && RATABLE.containsKey(p.getPlaceType()))
            .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"평가할 수 있는 장소를 찾지 못했어요. 관광지·음식점·숙소만 평가할 수 있어요."));
    }
    private static PlaceInfo info(Place p) {
        return new PlaceInfo(p.getId(),p.getName(),p.getPlaceType(),RATABLE.getOrDefault(p.getPlaceType(),"장소"),p.getAddress());
    }
    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
}
