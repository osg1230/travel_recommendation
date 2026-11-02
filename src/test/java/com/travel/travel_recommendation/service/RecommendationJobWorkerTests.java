package com.travel.travel_recommendation.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** 작업 하나가 잘못돼도 실행기가 멈추지 않고 뒤의 작업을 처리하는지 확인한다. */
class RecommendationJobWorkerTests {
    private final RecommendationJobService service = mock(RecommendationJobService.class);
    private final DestinationRecommendationService recommendations = mock(DestinationRecommendationService.class);
    private final ItineraryJobService itineraries = mock(ItineraryJobService.class);
    private final RecommendationJobWorker worker = new RecommendationJobWorker(service, recommendations, itineraries,
        mock(PurposePlaceService.class), mock(RegionSuggestionService.class), mock(DestinationPickService.class),
        mock(FestivalPlaceService.class), mock(PlacePhotoService.class), mock(NearbyRestaurantService.class),
        mock(PlacePickService.class), mock(DestinationLandmarkService.class), mock(DestinationProfileService.class));
    private final UUID broken = UUID.randomUUID();
    private final UUID next = UUID.randomUUID();

    @BeforeEach void start() { worker.start(); }

    @Test void unreadableItineraryRequestIsFailedAndNextJobRuns() {
        when(itineraries.queued()).thenReturn(List.of(broken, next));
        when(itineraries.claim(broken)).thenThrow(new IllegalStateException("읽을 수 없는 JSON"));
        worker.runItineraries();
        verify(itineraries).failClaim(broken);
        verify(itineraries).claim(next);
    }

    @Test void unreadableRecommendationRequestIsFailedAndNextJobRuns() {
        when(service.queued()).thenReturn(List.of());
        when(recommendations.queued()).thenReturn(List.of(broken, next));
        when(recommendations.claim(broken)).thenThrow(new IllegalStateException("읽을 수 없는 JSON"));
        worker.runRecommendations();
        verify(recommendations).failClaim(broken);
        verify(recommendations).claim(next);
    }

    // DB가 잠시 끊긴 것이면 나아질 수 있으므로 실패로 끝내지 않고 다음 차례에 다시 시도한다.
    @Test void databaseProblemLeavesJobForTheNextTurn() {
        when(itineraries.queued()).thenReturn(List.of(broken, next));
        when(itineraries.claim(broken)).thenThrow(new DataAccessResourceFailureException("연결 끊김"));
        worker.runItineraries();
        verify(itineraries, never()).failClaim(any());
        verify(itineraries).claim(next);
    }

    // 실패 표시마저 안 되는 경우에도 실행기는 예외로 끝나지 않고 뒤의 작업으로 넘어간다.
    @Test void failingToRecordFailureDoesNotStopTheLoop() {
        var work = mock(ItineraryJobService.Work.class);
        when(itineraries.queued()).thenReturn(List.of(broken, next));
        when(itineraries.claim(broken)).thenReturn(work);
        when(itineraries.generate(work)).thenThrow(new IllegalStateException("일정 오류"));
        doThrow(new DataAccessResourceFailureException("연결 끊김")).when(itineraries).fail(any(), any(), any());
        assertThatCode(worker::runItineraries).doesNotThrowAnyException();
        verify(itineraries).claim(next);
    }
}
