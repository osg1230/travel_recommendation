package com.travel.travel_recommendation.service;

import com.travel.travel_recommendation.dto.ItineraryPlan.*;
import com.travel.travel_recommendation.dto.PlaceCandidate;
import java.util.*;

/**
 * 일정 초안에서 서버가 확인하지 못해 "방문 전 확인이 필요해요"로 둔 항목을 AI 웹 검색 질문으로 모은다.
 * ① 반려동물 동반 여행인데 관광공사 동반 정보가 없는 장소, ② 사용자가 직접 말한 여행 목적(특정 활동)이 있는데
 * 그 활동이 되는지 확인하지 못한 관광지(목적 시설이 일정에 있으면 그곳만, 없으면 관광지 전체). 유료 검색이라 수를 제한한다.
 */
public final class VerificationQuestions {
    private VerificationQuestions() {}
    /** 한 초안에서 한 번에 묻는 질문 수 상한. 질문마다 웹 검색 1회가 든다. */
    static final int MAX_QUESTIONS = 10;

    public static List<CheckQuestion> of(Input input, Result result) {
        var conditions = input.conditions();
        var pool = new ArrayList<>(input.attractionsWithReserve());
        pool.addAll(input.places().restaurants()); pool.addAll(input.places().accommodations());
        var questions = new ArrayList<CheckQuestion>();
        var seen = new HashSet<Long>();
        var placed = new ArrayList<PlaceCandidate>();
        for (var day : result.days()) for (var item : day.items()) {
            if (!Set.of("VISIT", "MEAL", "STAY").contains(item.type()) || item.placeId() == null || !seen.add(item.placeId())) continue;
            pool.stream().filter(p -> p.placeId().equals(item.placeId())).findFirst().ifPresent(placed::add);
        }
        // ① 여행 목적 활동: 사용자가 직접 말한 목적만(서비스가 제안한 방향은 묻지 않는다). "강아지와 상어 관람"처럼 반려동물이 섞여 있어도 활동은 따로 묻는다.
        //    질문 수 상한에 걸려도 목적 질문이 먼저 들어가도록 반려동물 질문보다 앞에 둔다.
        String purpose = conditions.travelPurpose() == null ? "" : conditions.travelPurpose().strip();
        if (!purpose.isEmpty() && !conditions.purposeSuggested()) {
            var attractions = placed.stream().filter(p -> input.attractionsWithReserve().contains(p)).toList();
            var facilityIds = conditions.purposeSearch() == null ? Set.<Long>of()
                : conditions.purposeSearch().placesIn(input.places().destination().placeId()).stream().map(f -> f.placeId()).collect(java.util.stream.Collectors.toSet());
            var targets = attractions.stream().filter(p -> facilityIds.contains(p.placeId())).toList();
            if (targets.isEmpty()) targets = attractions;
            for (var place : targets)
                questions.add(new CheckQuestion(place.placeId(), place.name(), "PURPOSE",
                    place.address() + "에 있는 '" + place.name() + "'에서 '" + purpose + "'을(를) 할 수 있나요? (관람·체험 내용 기준)"));
        }
        // ② 반려동물 동반: 관광공사 정보가 없는 장소만 묻는다.
        if (PetTravel.wanted(conditions)) {
            for (var place : placed) {
                var pet = result.pets() == null ? null : result.pets().stream().filter(p -> p.placeId().equals(place.placeId())).findFirst().orElse(null);
                if (pet != null && !"UNKNOWN".equals(pet.status())) continue;
                questions.add(new CheckQuestion(place.placeId(), place.name(), "PET",
                    place.address() + "에 있는 '" + place.name() + "'에 반려동물(강아지) 동반 입장이나 이용이 가능한가요?"));
            }
        }
        return List.copyOf(questions.subList(0, Math.min(MAX_QUESTIONS, questions.size())));
    }
}
