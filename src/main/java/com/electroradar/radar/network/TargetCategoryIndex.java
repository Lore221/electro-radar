package com.electroradar.radar.network;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Индекс одного радара: счётчик секторных подтверждений каждой категории одной цели. */
final class TargetCategoryIndex {
    private final Map<TargetCategory, Map<TargetRef, Integer>> counts = new EnumMap<>(TargetCategory.class);

    void replace(TargetRef target, Set<TargetCategory> previous, Set<TargetCategory> next) {
        if (previous.equals(next)) return;
        for (TargetCategory category : previous) {
            if (next.contains(category)) continue;
            Map<TargetRef, Integer> targets = counts.get(category);
            int remaining = targets.get(target) - 1;
            if (remaining == 0) targets.remove(target); else targets.put(target, remaining);
            if (targets.isEmpty()) counts.remove(category);
        }
        for (TargetCategory category : next) {
            if (!previous.contains(category)) {
                counts.computeIfAbsent(category, unused -> new HashMap<>()).merge(target, 1, Integer::sum);
            }
        }
    }

    /** Внутреннее представление для синхронного запроса ядра; наружу выходит immutable snapshot. */
    Set<TargetRef> targets(TargetCategory category) {
        Map<TargetRef, Integer> targets = counts.get(category);
        return targets == null ? Set.of() : targets.keySet();
    }

    void clear() { counts.clear(); }
}
