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
 * 장소 평가 등록·요약·갱신·삭제(SFR-10-01~04). 별점 1~5점, 사전 정의 태그 최대 3개, 선택 입력 한줄평을 받으며,
 * 회원·장소당 한 건이라 다시 평가하면 기존 평가를 갱신한다. 요약의 최근 평가에는 평가자 닉네임을 가린 형태로만 넣고,
 * 이용 제한·탈퇴 회원의 한줄평은 빼고 보여 준다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class PlaceRatingService {
    public static final int MAX_TAGS = 3;
    /** 한줄평 최대 글자 수. DB 컬럼(NVARCHAR(200))보다 짧게 둔다. */
    public static final int MAX_COMMENT_LENGTH = 100;
    /** 장소별로 다른 회원에게 보여 주는 최근 평가 수. */
    public static final int RECENT_LIMIT = 5;
    /** 평가 둘러보기 화면에 한 번에 보여 주는 장소 수. */
    public static final int BROWSE_LIMIT = 30;
    private static final Map<String,String> RATABLE = Map.of("ATTRACTION","관광지","RESTAURANT","음식점","ACCOMMODATION","숙소");
    private final PlaceRatingRepository ratings;
    private final PlaceRatingTagRepository ratingTags;
    private final RatingTagRepository tags;
    private final PlaceRepository places;
    private final UserRepository users;

    @Transactional(readOnly=true,timeout=10)
    public Form form(Long placeId, String loginId) {
        Long userId=member(loginId);
        var mine=ratings.findByUserIdAndPlaceId(userId,placeId).map(r->new Mine(r.getScore(),
            ratingTags.findByRatingIdOrderBySlot(r.getId()).stream().map(PlaceRatingTag::getTagId).toList(),r.getComment(),r.getUpdatedAt())).orElse(null);
        // 더 이상 확인되지 않는(비활성) 장소도 내 평가가 있으면 화면을 열어 삭제할 수 있게 한다. 새 평가·변경은 save()가 막는다.
        var place=places.findById(placeId).filter(p->RATABLE.containsKey(p.getPlaceType()) && (p.isActive() || mine!=null))
            .orElseThrow(PlaceRatingService::notRatable);
        var active=activeTags();
        // 내 평가에 있지만 지금은 사용 중지된 태그. 화면에 체크칸이 없어 다시 저장하면 빠지므로 미리 알려 준다.
        var activeIds=active.stream().map(Tag::id).collect(Collectors.toSet());
        var dropped=new ArrayList<String>();
        if(mine!=null) {
            var names=tags.findAllById(mine.tagIds()).stream().collect(Collectors.toMap(RatingTag::getId,RatingTag::getName));
            for(Long tagId:mine.tagIds()) if(!activeIds.contains(tagId) && names.containsKey(tagId)) dropped.add(names.get(tagId));
        }
        return new Form(info(place),mine,active,summaries(List.of(placeId)).getOrDefault(placeId,Summary.EMPTY),List.copyOf(dropped));
    }

    /** 새 평가를 등록하거나, 이미 평가한 장소면 별점·태그·한줄평을 새 입력값으로 바꾼다. created=true면 새 평가다. */
    @Transactional(timeout=10)
    public boolean save(Long placeId, String loginId, Integer score, List<Long> tagIds, String comment) {
        Long userId=member(loginId);
        ratable(placeId);
        if(score==null || score<1 || score>5) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"별점을 1~5점 중에서 골라 주세요.");
        String text=normalizeComment(comment);
        var chosen=tagIds==null?List.<Long>of():tagIds;
        if(new HashSet<>(chosen).size()!=chosen.size()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"같은 태그를 두 번 고를 수 없어요.");
        if(chosen.size()>MAX_TAGS) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"태그는 최대 "+MAX_TAGS+"개까지 고를 수 있어요.");
        var allowed=activeTags().stream().map(Tag::id).collect(Collectors.toSet());
        if(!allowed.containsAll(chosen)) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"화면에 있는 태그에서만 골라 주세요.");
        var existing=ratings.lockByUserIdAndPlaceId(userId,placeId).orElse(null);
        PlaceRating rating;
        boolean created=existing==null;
        try {
            if(created) rating=ratings.saveAndFlush(new PlaceRating(userId,placeId,score,text));
            else { existing.rescore(score,text); rating=existing; ratingTags.deleteByRating(rating.getId()); }
        } catch(DataIntegrityViolationException duplicate) {
            // 같은 회원이 다른 창에서 동시에 처음 평가한 경우다. 기존 평가를 말없이 덮어쓰지 않고,
            // 컨트롤러가 방금 적은 값을 채운 평가 화면으로 돌려보내 확인한 뒤 다시 저장하게 한다(그때는 기존 평가 갱신이 된다).
            throw new ResponseStatusException(HttpStatus.CONFLICT,"다른 화면에서 방금 이 장소를 평가했어요. 적은 내용을 다시 채워 두었으니 확인하고 한 번 더 저장해 주세요.");
        }
        int slot=0;
        for(Long tagId:chosen) ratingTags.save(new PlaceRatingTag(rating.getId(),tagId,++slot));
        ratings.flush(); ratingTags.flush();
        return created;
    }

    /**
     * 본인 평가만 지운다. 지웠으면 true, 지울 평가가 없으면 false다.
     * 두 번 누르거나 다른 탭에서 먼저 지운 경우도 결과는 같으므로 오류로 보지 않는다. 비활성 장소의 평가도 지울 수 있다.
     */
    @Transactional(timeout=10)
    public boolean delete(Long placeId, String loginId) {
        Long userId=member(loginId);
        var rating=ratings.lockByUserIdAndPlaceId(userId,placeId).orElse(null);
        if(rating==null) return false;
        ratingTags.deleteByRating(rating.getId());
        ratings.delete(rating);
        ratings.flush();
        return true;
    }

    /** 저장 일정 상세에서 '내 평가 ★4'를 보여 주려고, 이 회원이 평가한 장소의 별점만 모은다. 비회원·이용 제한 회원은 빈 값이다. */
    @Transactional(readOnly=true,timeout=10)
    public Map<Long,Integer> myScores(Collection<Long> placeIds, String loginId) {
        if(loginId==null || placeIds==null || placeIds.isEmpty()) return Map.of();
        var user=users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE).orElse(null);
        if(user==null) return Map.of();
        return ratings.findByUserIdInAndPlaceIdIn(List.of(user.getId()),Set.copyOf(placeIds)).stream()
            .collect(Collectors.toMap(PlaceRating::getPlaceId,r->(int)r.getScore()));
    }

    /** 저장·삭제 안내 문구에 넣을 장소 이름. 장소를 찾지 못하면 '장소'라고 쓴다. */
    @Transactional(readOnly=true,timeout=10)
    public String placeName(Long placeId) {
        return places.findById(placeId).map(Place::getName).orElse("장소");
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
            byRating.getOrDefault(r.getId(),List.of()).stream().map(t->names.get(t.getTagId())).toList(),r.getComment(),r.getUpdatedAt())).toList();
    }

    /** 장소별 평균 별점·평가 수·많이 고른 태그 3개와 최근 평가 최대 5건. 비회원도 볼 수 있다(SFR-10-02). */
    @Transactional(readOnly=true,timeout=10)
    public Map<Long,Summary> summaries(Collection<Long> placeIds) {
        if(placeIds==null || placeIds.isEmpty()) return Map.of();
        var ids=List.copyOf(new LinkedHashSet<>(placeIds));
        var names=tags.findAll().stream().collect(Collectors.toMap(RatingTag::getId,RatingTag::getName));
        Map<Long,List<TagCount>> tagCounts=new HashMap<>();
        for(var row:ratingTags.countTags(ids))
            tagCounts.computeIfAbsent((Long)row[0],k->new ArrayList<>()).add(new TagCount(names.get((Long)row[1]),((Number)row[2]).longValue()));
        var recentByPlace=recent(ids,names);
        var result=new HashMap<Long,Summary>();
        for(var row:ratings.summarize(ids)) {
            Long placeId=(Long)row[0];
            double average=Math.round(((Number)row[1]).doubleValue()*10)/10.0;
            var top=tagCounts.getOrDefault(placeId,List.of()).stream()
                .sorted(Comparator.comparingLong(TagCount::count).reversed().thenComparing(TagCount::name)).limit(3).toList();
            result.put(placeId,new Summary(average,((Number)row[2]).longValue(),top,recentByPlace.getOrDefault(placeId,List.of())));
        }
        return result;
    }

    /** 평가 둘러보기: 최근에 평가가 남은 장소부터 최대 BROWSE_LIMIT곳과 각 장소의 요약·최근 평가. keyword가 있으면 장소 이름에 그 글자가 든 곳만. */
    @Transactional(readOnly=true,timeout=10)
    public List<RatedPlace> browse(String keyword) {
        String name=keyword==null?"":keyword.strip();
        var ids=ratings.findRatedPlaceIds(name,org.springframework.data.domain.PageRequest.of(0,BROWSE_LIMIT));
        if(ids.isEmpty()) return List.of();
        var placeById=places.findAllById(ids).stream().collect(Collectors.toMap(Place::getId,p->p));
        var summaryById=summaries(ids);
        return ids.stream().filter(placeById::containsKey)
            .map(id->new RatedPlace(info(placeById.get(id)),summaryById.getOrDefault(id,Summary.EMPTY))).toList();
    }

    /**
     * 장소별 최근 평가 최대 RECENT_LIMIT건(별점·태그·한줄평·가린 닉네임). 평가는 최근에 바꾼 순서로 온다.
     * 이용 제한·탈퇴 회원의 한줄평은 공개하지 않는다(부적절한 한줄평은 회원 이용 제한으로 대응). 별점·태그는 그대로 보여 주고 집계에도 넣는다.
     */
    private Map<Long,List<Recent>> recent(List<Long> placeIds, Map<Long,String> tagNames) {
        Map<Long,List<PlaceRating>> byPlace=new HashMap<>();
        for(var rating:ratings.findByPlaceIdInOrderByUpdatedAtDescIdDesc(placeIds)) {
            var picked=byPlace.computeIfAbsent(rating.getPlaceId(),k->new ArrayList<>());
            if(picked.size()<RECENT_LIMIT) picked.add(rating);
        }
        var picked=byPlace.values().stream().flatMap(List::stream).toList();
        if(picked.isEmpty()) return Map.of();
        var tagsByRating=ratingTags.findByRatingIdIn(picked.stream().map(PlaceRating::getId).toList()).stream()
            .sorted(Comparator.comparingInt(PlaceRatingTag::getSlot)).collect(Collectors.groupingBy(PlaceRatingTag::getRatingId));
        var raters=users.findAllById(picked.stream().map(PlaceRating::getUserId).distinct().toList()).stream()
            .collect(Collectors.toMap(User::getId,u->u));
        Map<Long,List<Recent>> result=new HashMap<>();
        byPlace.forEach((placeId,list)->result.put(placeId,list.stream().map(r->{
            var rater=raters.get(r.getUserId());
            String comment=rater!=null && rater.getStatus()==UserStatus.ACTIVE?r.getComment():null;
            return new Recent(maskNickname(rater==null?null:rater.getNickname()),r.getScore(),
                tagsByRating.getOrDefault(r.getId(),List.of()).stream().map(t->tagNames.get(t.getTagId())).toList(),comment,r.getUpdatedAt());
        }).toList()));
        return result;
    }

    /** 한줄평은 앞뒤 공백을 지우고 비어 있으면 null로 저장한다. MAX_COMMENT_LENGTH를 넘으면 거절한다. */
    public static String normalizeComment(String comment) {
        if(comment==null) return null;
        String text=comment.strip();
        if(text.isEmpty()) return null;
        if(text.length()>MAX_COMMENT_LENGTH)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"한줄평은 "+MAX_COMMENT_LENGTH+"자까지 적을 수 있어요.");
        return text;
    }

    /** 다른 회원에게 보여 줄 닉네임은 앞 글자만 남기고 가린다(예: 김여행 → 김**). 이모지처럼 두 char인 글자도 한 글자로 센다. */
    public static String maskNickname(String nickname) {
        String text=nickname==null?"":nickname.strip();
        if(text.isEmpty()) return "**";
        return text.substring(0,Character.charCount(text.codePointAt(0)))+"**";
    }

    private List<Tag> activeTags() {
        return tags.findByActiveTrueOrderBySortOrderAscIdAsc().stream().map(t->new Tag(t.getId(),t.getName())).toList();
    }
    private Place ratable(Long placeId) {
        return places.findById(placeId).filter(p->p.isActive() && RATABLE.containsKey(p.getPlaceType()))
            .orElseThrow(PlaceRatingService::notRatable);
    }
    private static ResponseStatusException notRatable() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND,"평가할 수 있는 장소를 찾지 못했어요. 관광지·음식점·숙소만 평가할 수 있어요.");
    }
    private static PlaceInfo info(Place p) {
        // 지도 주소가 없는 장소는 주소와 이름으로 카카오맵 검색을 연다(확정 위치가 아니라 검색 결과다).
        String mapUrl=p.getMapUrl();
        if(mapUrl==null || mapUrl.isBlank()) {
            String query=((p.getAddress()==null?"":p.getAddress())+" "+p.getName()).strip();
            mapUrl="https://map.kakao.com/link/search/"+java.net.URLEncoder.encode(query,java.nio.charset.StandardCharsets.UTF_8).replace("+","%20");
        }
        return new PlaceInfo(p.getId(),p.getName(),p.getPlaceType(),RATABLE.getOrDefault(p.getPlaceType(),"장소"),p.getAddress(),
            mapUrl,p.getSourceUrl(),p.isActive());
    }
    private Long member(String loginId) {
        if(loginId==null) throw new AccessDeniedException("Login required");
        return users.findByLoginId(loginId).filter(u->u.getStatus()==UserStatus.ACTIVE)
            .map(User::getId).orElseThrow(()->new AccessDeniedException("Account unavailable"));
    }
}
