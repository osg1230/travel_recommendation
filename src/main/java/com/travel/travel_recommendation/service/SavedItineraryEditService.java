package com.travel.travel_recommendation.service;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/**
 * 저장한 일정을 수정할 초안을 준비한다. 원래 초안이 남아 있으면 그대로 쓰고,
 * 만료됐거나 추천 이력 삭제로 사라졌으면 저장한 조건·항목으로 수정용 초안을 다시 만든다.
 */
@Service
@Profile({"sqlserver","sqlserver-windows"})
@RequiredArgsConstructor
public class SavedItineraryEditService {
    private final SavedItineraryService saved;
    private final RecommendationJobService recommendations;
    private final PlaceDiscoveryService discovery;
    private static final org.slf4j.Logger log=org.slf4j.LoggerFactory.getLogger(SavedItineraryEditService.class);
    /** 이름으로 다시 찾는 저장 관광지 수의 상한. 카카오 검색 한 번씩이라 수정 준비가 너무 길어지지 않게 한다. */
    static final int RECHECK_LIMIT=8;

    // 외부 장소 조회 동안 DB 잠금을 잡지 않도록 이 메서드에는 트랜잭션을 두지 않는다.
    public SavedItineraryService.EditDraft prepare(Long id, String loginId) {
        var source=saved.editSource(id,loginId);
        if(source.editableJobId()!=null) return new SavedItineraryService.EditDraft(source.editableJobId(),List.of());
        var parent=recommendations.restoreForEdit(source.conditions(),source.destination(),loginId);
        // 교체할 관광지 후보를 지금 기준으로 다시 확인한다. 조회에 실패해도 이미 확인된 장소로 초안을 만든다.
        try { discovery.refresh(parent,source.destination().placeId(),loginId,null); }
        catch(RuntimeException unavailable) { log.warn("수정 초안용 장소 재조회 실패 itinerary={} ({})",id,unavailable.toString()); }
        // 지역 검색에 다시 나오지 않아 확인이 끝난 관광지는 이름으로 한 곳씩 다시 찾는다.
        // 확인되면 그대로 유지하고, 끝내 못 찾은 곳만 다시 만든 초안에서 바꾸도록 안내한다.
        String region=source.destination().region();
        for(String name:saved.unverifiedAttractions(id,loginId,region).stream().limit(RECHECK_LIMIT).toList()) {
            try { discovery.findAttraction(source.destination().placeId(),region,name); }
            catch(RuntimeException unavailable) { log.warn("저장 관광지 재확인 실패 itinerary={} ({})",id,unavailable.toString()); }
        }
        return saved.restoreDraft(id,parent,loginId);
    }
}
