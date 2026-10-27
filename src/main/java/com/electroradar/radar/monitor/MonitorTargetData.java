package com.electroradar.radar.monitor;

import java.util.Objects;

import com.electroradar.radar.network.TargetRef;

/** Серверное состояние отметки: отдельные факт наблюдения, sample и зафиксированное затухание. */
public record MonitorTargetData(TargetRef target, boolean observing, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                long sampledTick, long freezeTick, long fadeDurationTicks) {
    public MonitorTargetData {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        if (sampledTick < 0 || freezeTick < -1 || fadeDurationTicks < 0
                || observing && (!hasPosition || freezeTick != -1)
                || hasPosition && !observing && freezeTick < 0
                || hasPosition && (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                || !hasPosition && !entityTypeId.isEmpty()) {
            throw new IllegalArgumentException("Invalid monitor target state");
        }
    }

    /** Сохраняет старое имя как алиас для экранных компонентов, переводимых на observing. */
    @Deprecated
    public boolean detectedThisWindow() { return observing; }

    public MonitorTargetData(TargetRef target, boolean detectedThisWindow, boolean hasPosition,
                             double x, double y, double z, String entityTypeId) {
        this(target, detectedThisWindow, hasPosition, x, y, z, entityTypeId,
                0, detectedThisWindow || !hasPosition ? -1 : 0, 0);
    }
}
