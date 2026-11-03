package com.electroradar.cannon.diagnostics;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import com.electroradar.cannon.ballistics.Vector3;

/** Сопоставление по месту появления и времени. Неоднозначный выбор никогда не угадывается. */
final class ShotAssociation<T> {
    private static final int MAX_PENDING = 1024;
    private final Map<String, Pending<T>> pending = new LinkedHashMap<>();

    void remember(String controller, String dimension, long tick, Vector3 spawn, T value) {
        pending.remove(controller);
        if (pending.size() >= MAX_PENDING) pending.remove(pending.keySet().iterator().next());
        pending.put(controller, new Pending<>(dimension, tick, spawn, value));
    }

    Optional<T> match(String dimension, long tick, Vector3 spawn) {
        String found = null;
        boolean ambiguous = false;
        Iterator<Map.Entry<String, Pending<T>>> iterator = pending.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            var shot = entry.getValue();
            if (!shot.dimension().equals(dimension)) continue;
            if (tick < shot.tick() || tick - shot.tick() > 2) {
                iterator.remove();
                continue;
            }
            if (shot.spawn().subtract(spawn).length() <= 1.0E-3D) {
                if (found != null) ambiguous = true;
                found = entry.getKey();
            }
        }
        return found == null || ambiguous ? Optional.empty() : Optional.of(pending.remove(found).value());
    }

    void clear() {
        pending.clear();
    }

    private record Pending<T>(String dimension, long tick, Vector3 spawn, T value) {}
}
