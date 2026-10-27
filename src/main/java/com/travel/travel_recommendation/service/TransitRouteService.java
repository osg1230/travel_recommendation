package com.travel.travel_recommendation.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 대중교통 일정 지도의 일차별 실제 이동 경로(SFR-11-03, 2026-10-28).
 * 카카오 정책상 대중교통·도보 경로는 저장하거나 임시 캐시해 다시 쓸 수 없어서,
 * 화면에서 '실제 경로 보기'를 누를 때마다 그날 구간만 새로 조회하고 결과는 응답으로만 돌려준다.
 */
@Service
@RequiredArgsConstructor
public class TransitRouteService {
    /** 한 번에 조회할 수 있는 구간 수. 하루 방문지는 많아야 10곳 안팎이라 이보다 많으면 요청을 거절한다. */
    static final int MAX_LEGS = 15;
    /** 카카오에 동시에 보내는 요청 수. 구간마다 최대 3번(대중교통 1번, 도보 2번) 부르므로 한꺼번에 몰리지 않게 줄인다. */
    private static final int PARALLEL = 4;

    /** legs[n]은 stops[n]에서 stops[n+1]까지의 경로 조각들이다. 찾지 못한 구간은 빈 목록이라 화면에서 직선으로 남긴다. */
    public record DayPath(int day, List<List<KakaoMapClient.PathPiece>> legs, int failed) {}

    private final ObjectProvider<KakaoMapClient> maps;

    public DayPath day(TripMap.DayRoute day) {
        var client = maps.getIfAvailable();
        if (client == null) throw new RecommendationException("MAP_NOT_CONFIGURED", "지도 API 연결 설정이 필요해요.");
        var stops = day.stops();
        if (stops.size() - 1 > MAX_LEGS) throw new RecommendationException("MAP_TOO_MANY", "한 번에 볼 수 있는 구간 수를 넘었어요.");
        var legs = new ArrayList<List<KakaoMapClient.PathPiece>>();
        int failed = 0;
        // 구간들을 몇 개씩 동시에 조회해 기다리는 시간을 줄인다. 한 구간이 실패해도 나머지는 그린다.
        try (var pool = Executors.newFixedThreadPool(PARALLEL)) {
            var jobs = new ArrayList<Future<List<KakaoMapClient.PathPiece>>>();
            for (int i = 0; i + 1 < stops.size(); i++) {
                var from = location(stops.get(i));
                var to = location(stops.get(i + 1));
                boolean same = from.latitude() == to.latitude() && from.longitude() == to.longitude();
                jobs.add(same ? null : pool.submit(() -> client.transitPath(from, to)));
            }
            for (var job : jobs) {
                if (job == null) { legs.add(List.of()); continue; }
                try {
                    legs.add(job.get());
                } catch (ExecutionException e) {
                    legs.add(List.of());
                    failed++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new RecommendationException("MAP_INTERRUPTED", "지도 조회가 중단됐어요.");
                }
            }
        }
        return new DayPath(day.number(), List.copyOf(legs), failed);
    }

    private static KakaoMapClient.Location location(TripMap.Point stop) {
        String name = stop.name() == null || stop.name().isBlank() ? "장소" : stop.name();
        return new KakaoMapClient.Location(name, stop.lng(), stop.lat(), Instant.now());
    }
}
