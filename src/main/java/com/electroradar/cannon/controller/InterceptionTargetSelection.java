package com.electroradar.cannon.controller;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Удержание угрозы своего Alarm и временное откладывание необстреливаемых целей. */
final class InterceptionTargetSelection {
    private static final long REJECT_TICKS = 20;
    private static final Set<FireStatus> REJECTING = EnumSet.of(FireStatus.TARGET_LOST,
            FireStatus.TARGET_UNSUPPORTED, FireStatus.TARGET_UNREACHABLE, FireStatus.TOO_LATE,
            FireStatus.TRAJECTORY_BLOCKED, FireStatus.TRAJECTORY_UNLOADED);
    private final Map<UUID, Long> rejected = new HashMap<>();
    private UUID current;

    UUID select(Map<UUID, Integer> threats, long now) {
        rejected.values().removeIf(until -> until <= now);
        if (current == null || !threats.containsKey(current) || rejected.containsKey(current)) {
            current = threats.entrySet().stream()
                    .filter(threat -> !rejected.containsKey(threat.getKey()))
                    .min(Map.Entry.<UUID, Integer>comparingByValue().thenComparing(Map.Entry.comparingByKey()))
                    .map(Map.Entry::getKey).orElse(null);
        }
        return current;
    }

    void recordOutcome(FireStatus status, long now) {
        if (current != null && REJECTING.contains(status)) {
            rejected.put(current, now + REJECT_TICKS);
            current = null;
        }
    }

    void clear() {
        current = null;
        rejected.clear();
    }
}
