package com.travel.travel_recommendation.service;

import java.util.*;
import java.util.function.ToDoubleBiFunction;

/**
 * 하루 동선. 그날 들를 곳들을 도는 순서 중 이동 거리가 가장 짧은 순서를 찾는다(2026-10-31).
 * 하루 관광지는 보통 4곳 이하라(4곳이면 24가지) 가능한 순서를 모두 비교한다. 거리는 두 장소 사이 거리를 구하는 함수(leg)로 받는다.
 * 일정 생성(ItineraryGenerator)과 품질 측정(ItineraryQualityReportTests)이 같은 계산을 쓴다.
 */
public final class DayRoute {
    private DayRoute() {}
    /** 모든 순서를 비교하는 최대 장소 수. 6곳이면 720가지다. 이보다 많으면 순서를 바꾸지 않는다. */
    public static final int MAX_STOPS = 6;

    /** start → stops(차례대로) → end 거리의 합. start·end가 null이면 그 구간은 세지 않는다(출발지·도착지를 모르는 날). */
    public static <T> double length(List<T> stops, T start, T end, ToDoubleBiFunction<T, T> leg) {
        double total = 0;
        T previous = start;
        for (T stop : stops) {
            if (previous != null) total += leg.applyAsDouble(previous, stop);
            previous = stop;
        }
        if (end != null && previous != null) total += leg.applyAsDouble(previous, end);
        return total;
    }

    /**
     * start에서 출발해 stops를 모두 들르고 end에서 끝나는 가장 짧은 순서. 거리가 같으면 원래 순서에 가까운 쪽을 고른다.
     * 장소가 MAX_STOPS곳보다 많으면 원래 순서를 그대로 돌려준다.
     */
    public static <T> List<T> shortest(List<T> stops, T start, T end, ToDoubleBiFunction<T, T> leg) {
        if (stops.size() < 2 || stops.size() > MAX_STOPS) return List.copyOf(stops);
        var search = new Search<>(stops, end, leg);
        search.best = List.copyOf(stops);
        search.bestLength = length(stops, start, end, leg);
        search.visit(new ArrayList<>(), new boolean[stops.size()], start, 0);
        return search.best;
    }

    /** 순서를 하나씩 만들어 보며 지금까지 찾은 가장 짧은 순서보다 길어지면 그 갈래는 더 보지 않는다. */
    private static final class Search<T> {
        final List<T> stops; final T end; final ToDoubleBiFunction<T, T> leg;
        List<T> best; double bestLength;
        Search(List<T> stops, T end, ToDoubleBiFunction<T, T> leg) { this.stops = stops; this.end = end; this.leg = leg; }

        void visit(List<T> order, boolean[] used, T previous, double sofar) {
            if (sofar >= bestLength) return;
            if (order.size() == stops.size()) {
                double total = sofar + (end == null ? 0 : leg.applyAsDouble(previous, end));
                // 아주 작은 차이(계산 오차)로는 바꾸지 않는다.
                if (total < bestLength - 1e-9) { bestLength = total; best = List.copyOf(order); }
                return;
            }
            for (int i = 0; i < stops.size(); i++) {
                if (used[i]) continue;
                T next = stops.get(i);
                used[i] = true; order.add(next);
                visit(order, used, next, sofar + (previous == null ? 0 : leg.applyAsDouble(previous, next)));
                order.removeLast(); used[i] = false;
            }
        }
    }
}
