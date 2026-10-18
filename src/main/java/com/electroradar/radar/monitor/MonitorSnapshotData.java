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
 *
 * <p>Порядок снимков задаёт только {@code sequence}: счётчик корня, который растёт при каждой
 * пересборке снимка в пределах одной загрузки. Epoch и revision сети для этого не годятся:
 * после ухода последнего радара сеть начинает epoch заново с нуля, а у монитора без связи
 * пустой снимок имеет revision 0. Если сравнивать по ним, клиент отбросит такой снимок как
 * устаревший и продолжит показывать исчезнувшие цели.</p>
 */
public record MonitorSnapshotData(
        UUID displayId,
        BlockPos rootPosition,
        ResourceLocation dimension,
        UUID loadId,
        long sequence,
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
        if (sequence < 0 || structureVersion < 0 || publishedEpoch < 0 || revision < 0) {
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
