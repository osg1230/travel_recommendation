package com.travel.travel_recommendation.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 외부 유료·한도 호출 결과를 서버 메모리에 잠시 보관한다. 서버를 다시 켜면 비워지며, 실패한 호출은 보관하지 않는다.
 * 같은 키를 동시에 처음 조회하면 두 번 호출될 수 있지만 결과는 같아 막지 않는다.
 */
class ExpiringCache<K, V> {
    private record Entry<V>(V value, Instant expiresAt) {}
    private final Map<K, Entry<V>> entries = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final int maxEntries;
    private final Supplier<Instant> clock;

    ExpiringCache(Duration ttl, int maxEntries) { this(ttl, maxEntries, Instant::now); }
    ExpiringCache(Duration ttl, int maxEntries, Supplier<Instant> clock) {
        this.ttl = ttl; this.maxEntries = maxEntries; this.clock = clock;
    }

    /** 보관 중인 값이 있으면 그대로, 없거나 만료됐으면 load로 새로 받아 보관한다. load가 실패하면 예외를 그대로 던진다. */
    V get(K key, Supplier<V> load) {
        Instant now = clock.get();
        var entry = entries.get(key);
        if (entry != null && now.isBefore(entry.expiresAt())) return entry.value();
        V value = load.get();
        if (entries.size() >= maxEntries) {
            entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
            // 모두 유효한데도 가득 찼으면 비우고 다시 쌓는다. 호출이 한 번씩 더 생길 뿐 결과는 달라지지 않는다.
            if (entries.size() >= maxEntries) entries.clear();
        }
        entries.put(key, new Entry<>(value, now.plus(ttl)));
        return value;
    }

    /** 이 키를 지금 보관하고 있거나, 만료된 값을 정리하면 새로 넣을 자리가 있는지. 가득 찼을 때 새 키를 받지 않으려는 쪽에서 쓴다. */
    boolean hasRoomFor(K key) {
        if (peek(key) != null || entries.size() < maxEntries) return true;
        Instant now = clock.get();
        entries.values().removeIf(e -> !now.isBefore(e.expiresAt()));
        return entries.size() < maxEntries;
    }

    /** 보관 중이고 만료되지 않은 값만 돌려준다. 없으면 null이며 새로 불러오지 않는다. */
    V peek(K key) {
        var entry = entries.get(key);
        return entry != null && clock.get().isBefore(entry.expiresAt()) ? entry.value() : null;
    }

    void remove(K key) { entries.remove(key); }

    void clear() { entries.clear(); }
}
