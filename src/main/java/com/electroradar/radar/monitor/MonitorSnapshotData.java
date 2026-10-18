package com.electroradar.radar.monitor;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.network.TargetRef;

/**
 * Неизменяемый снимок одного физического монитора, готовый к клиентской отрисовке.
 *
 * <p>Runtime UUID отличает текущую загрузку корня от следующей загрузки того же блока.
 * Сочетание измерения, позиции, DisplayId и UUID загрузки не позволяет применить запоздавшую
 * часть пакета к новому экрану. В снимке нет Entity, сетевых токенов и подробных источников.</p>
 */
public record MonitorSnapshotData(
        UUID displayId,
        BlockPos rootPosition,
        ResourceLocation dimension,
        UUID loadId,
        long structureVersion,
        long publishedEpoch,
        long revision,
        boolean linked,
        boolean selectedTargetUnavailable,
        TargetRef selectedTarget,
        List<MonitorTargetData> targets
) {
    public MonitorSnapshotData {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        if (structureVersion < 0 || publishedEpoch < 0 || revision < 0) {
            throw new IllegalArgumentException("Monitor snapshot versions must be non-negative");
        }
        targets = List.copyOf(targets);
        if (selectedTarget != null && targets.stream().noneMatch(target -> target.target().equals(selectedTarget))) {
            throw new IllegalArgumentException("A selected target must be present in this monitor snapshot");
        }
        if (selectedTarget != null && selectedTargetUnavailable) {
            throw new IllegalArgumentException("An available selected target cannot also be marked unavailable");
        }
    }
}
