package com.electroradar.radar.monitor;

import java.util.Objects;

import com.electroradar.radar.network.TargetRef;

/**
 * Отметка цели на мониторе: факт наблюдения, последнее разрешённое положение и затухание.
 *
 * <p>Времени снятия sample здесь нет: клиенту оно не нужно, а у наблюдаемой цели оно меняется
 * каждый тик и превращало бы неподвижную цель в постоянный поток обновлений.</p>
 */
public record MonitorTargetData(TargetRef target, boolean observing, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                long freezeTick, long fadeDurationTicks) {
    public MonitorTargetData {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        if (freezeTick < -1 || fadeDurationTicks < 0
                || observing && (!hasPosition || freezeTick != -1)
                || hasPosition && !observing && freezeTick < 0
                || hasPosition && (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                || !hasPosition && !entityTypeId.isEmpty()) {
            throw new IllegalArgumentException("Invalid monitor target state");
        }
    }

    /** Старое имя для экранных компонентов. */
    public boolean detectedThisWindow() { return observing; }
}
