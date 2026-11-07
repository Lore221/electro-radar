package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ответ на запрос одной цели; {@code contentVersion} с теми же гарантиями, что у {@link TargetSnapshot}. */
public record TargetSources(
        TargetRef target,
        long contentVersion,
        List<RadarSource> sources,
        Optional<LiveResult> liveResult,
        boolean registeredStructure
) {
    public TargetSources {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(liveResult, "liveResult");
        if (contentVersion < 0) throw new IllegalArgumentException("Snapshot version must be non-negative");
        sources = List.copyOf(sources);
        if (registeredStructure && target.sourceKind() != TargetRef.SourceKind.MOVING_STRUCTURE)
            throw new IllegalArgumentException("Only structures have network placement access");
        if (sources.isEmpty() && liveResult.isPresent() && !registeredStructure) {
            throw new IllegalArgumentException("A target without accessible sources cannot expose live data");
        }
    }

    public TargetSources(TargetRef target, long contentVersion, List<RadarSource> sources, Optional<LiveResult> liveResult) {
        this(target, contentVersion, sources, liveResult, false);
    }

    /** Показывает, есть ли у цели хотя бы один доступный этому потребителю радарный источник. */
    public boolean confirmed() {
        return registeredStructure || !sources.isEmpty();
    }
}
