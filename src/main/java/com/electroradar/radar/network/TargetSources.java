package com.electroradar.radar.network;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Ответ на запрос одной цели; {@code version} — та же версия сети, что у {@link TargetSnapshot}. */
public record TargetSources(
        TargetRef target,
        long version,
        List<RadarSource> sources,
        Optional<LiveResult> liveResult
) {
    public TargetSources {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(liveResult, "liveResult");
        if (version < 0) throw new IllegalArgumentException("Snapshot version must be non-negative");
        sources = List.copyOf(sources);
        if (sources.isEmpty() && liveResult.isPresent()) {
            throw new IllegalArgumentException("A target without accessible sources cannot expose live data");
        }
    }

    /** Показывает, есть ли у цели хотя бы один доступный этому потребителю радарный источник. */
    public boolean confirmed() {
        return !sources.isEmpty();
    }
}
