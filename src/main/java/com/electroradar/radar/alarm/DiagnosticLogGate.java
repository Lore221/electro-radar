package com.electroradar.radar.alarm;

import java.util.LinkedHashMap;
import java.util.Objects;

/** Ограничитель повторов и памяти журнала; от игровой классификации не зависит. */
final class DiagnosticLogGate<K> {
    private final int capacity;
    private final long interval;
    private final LinkedHashMap<K, Stamp> stamps = new LinkedHashMap<>(16, .75F, true);

    DiagnosticLogGate(int capacity, long interval) {
        if (capacity < 1 || interval < 1) throw new IllegalArgumentException("Invalid diagnostic limits");
        this.capacity = capacity;
        this.interval = interval;
    }

    boolean due(K key, Object signature, long tick) {
        Stamp previous = stamps.get(key);
        return previous == null || !Objects.equals(previous.signature(), signature)
                || tick < previous.tick() || tick - previous.tick() >= interval;
    }

    void sent(K key, Object signature, long tick) {
        stamps.put(key, new Stamp(signature, tick));
        while (stamps.size() > capacity) stamps.remove(stamps.keySet().iterator().next());
    }

    int size() { return stamps.size(); }
    private record Stamp(Object signature, long tick) { }
}
