package com.electroradar.radar.monitor;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualProfile;

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
        List<MonitorTargetData> targets,
        List<RadarVisualProfile> visualProfiles,
        long measurementRevision,
        long viewGeneration,
        long observedServerTick,
        List<MonitorVisualPhase> visualPhases
) {
    public MonitorSnapshotData {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        if (sequence < 0 || structureVersion < 0 || publishedEpoch < 0 || revision < 0
                || measurementRevision < 0 || viewGeneration < 0 || observedServerTick < 0) {
            throw new IllegalArgumentException("Monitor snapshot versions must be non-negative");
        }
        targets = List.copyOf(targets);
        visualProfiles = List.copyOf(visualProfiles);
        visualPhases = List.copyOf(visualPhases);
        if (!visualProfiles.isEmpty() && !linked) throw new IllegalArgumentException("Unlinked monitor has radar profiles");
        Set<RadarVisualProfile.Key> profileKeys = new HashSet<>();
        if (visualProfiles.stream().anyMatch(profile -> !profile.dimension().equals(dimension))) {
            throw new IllegalArgumentException("Radar visual profile belongs to another dimension");
        }
        for (RadarVisualProfile profile : visualProfiles) {
            if (!profileKeys.add(profile.key())) {
                throw new IllegalArgumentException("Monitor snapshot contains a duplicate radar profile");
            }
        }
        Set<TargetRef> targetRefs = new HashSet<>();
        for (MonitorTargetData target : targets) {
            if (!target.target().dimension().location().equals(dimension) || !targetRefs.add(target.target())) {
                throw new IllegalArgumentException("Monitor snapshot targets must be unique and same-dimension");
            }
        }
        Set<BlockPos> phasePositions = new HashSet<>();
        for (MonitorVisualPhase phase : visualPhases) {
            if (!phasePositions.add(phase.radarPosition())
                    || visualProfiles.stream().noneMatch(profile -> profile.radarPosition().equals(phase.radarPosition())
                    && phase.activeSectorIndex() < profile.sectorCount())) {
                throw new IllegalArgumentException("Monitor phase has no unique geometry profile");
            }
        }
        if (selectedTarget != null && targets.stream().noneMatch(target -> target.target().equals(selectedTarget))) {
            throw new IllegalArgumentException("A selected target must be present in this monitor snapshot");
        }
        if (selectedTarget != null && selectedTargetUnavailable) {
            throw new IllegalArgumentException("An available selected target cannot also be marked unavailable");
        }
    }

    /** Перевязывает серверный тик к моменту адресного baseline для расчёта возраста freeze. */
    public MonitorSnapshotData withServerTiming(long serverTick) {
        if (serverTick < 0) throw new IllegalArgumentException("Server tick must be non-negative");
        return new MonitorSnapshotData(displayId, rootPosition, dimension, loadId, sequence,
                structureVersion, publishedEpoch, revision, linked, selectedTargetUnavailable,
                selectedTarget, targets, visualProfiles, measurementRevision, viewGeneration,
                serverTick, visualPhases);
    }
}
