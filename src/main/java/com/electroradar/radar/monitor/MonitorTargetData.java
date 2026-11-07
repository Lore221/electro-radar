package com.electroradar.radar.monitor;

import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.spatial.ScanAnchor;

import java.util.Objects;
import java.util.Optional;

import com.electroradar.radar.network.TargetRef;

/**
 * Отметка цели на мониторе: факт наблюдения, последнее разрешённое положение и затухание.
 *
 * <p>Времени снятия sample здесь нет: клиенту оно не нужно, а у наблюдаемой цели оно меняется
 * каждый тик и превращало бы неподвижную цель в постоянный поток обновлений.</p>
 */
public record MonitorTargetData(TargetRef target, boolean observing, boolean hasPosition,
                                double x, double y, double z, String entityTypeId,
                                long freezeTick, long fadeDurationTicks, Silhouette silhouette,
                                boolean registeredStructure, Optional<ScanAnchor> scanAnchor) {
    public MonitorTargetData {
        Objects.requireNonNull(silhouette, "silhouette");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(entityTypeId, "entityTypeId");
        Objects.requireNonNull(scanAnchor, "scanAnchor");
        if (freezeTick < -1 || fadeDurationTicks < 0
                || observing && (!hasPosition || freezeTick != -1)
                || hasPosition && !observing && freezeTick < 0
                || hasPosition && (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z))
                || !hasPosition && !entityTypeId.isEmpty()
                || registeredStructure && (target.sourceKind() != TargetRef.SourceKind.MOVING_STRUCTURE || !observing)
                || scanAnchor.isPresent() && (target.sourceKind() != TargetRef.SourceKind.MOVING_STRUCTURE || !hasPosition
                        || !scanAnchor.get().radar().dimension().equals(target.dimension()))) {
            throw new IllegalArgumentException("Invalid monitor target state");
        }
    }

    public MonitorTargetData(TargetRef target, boolean observing, boolean hasPosition,
            double x, double y, double z, String entityTypeId, long freezeTick, long fadeDurationTicks,
            Silhouette silhouette) {
        this(target, observing, hasPosition, x, y, z, entityTypeId, freezeTick, fadeDurationTicks,
                silhouette, false, Optional.empty());
    }

    public MonitorTargetData(TargetRef target, boolean observing, boolean hasPosition,
            double x, double y, double z, String entityTypeId, long freezeTick, long fadeDurationTicks) {
        this(target, observing, hasPosition, x, y, z, entityTypeId, freezeTick, fadeDurationTicks,
                Silhouette.EMPTY);
    }

    /** Старое имя для экранных компонентов. */
    public boolean detectedThisWindow() { return observing; }
}
