package com.electroradar.radar.monitor;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Неизменяемый полный снимок одного физического монитора.
 *
 * <p><b>Идентичность.</b> Измерение, позиция корня, DisplayId и loadId (новый на каждую загрузку
 * корня) не позволяют применить снимок к другому экрану или следующей загрузке того же блока.</p>
 *
 * <p><b>Версии.</b> Их две, и у каждой одна задача. {@code sequence} упорядочивает снимки одной
 * загрузки: клиент принимает только больший номер. {@code geometryVersion} растёт, когда меняются
 * профили радаров. Профили тяжёлые, поэтому рассылка их не повторяет, пока версия прежняя.
 * Номера сетевых окон и ревизий ядра в снимок не входят: они нужны серверу, а не экрану.</p>
 *
 * <p>{@code observedServerTick} — тик, в который снимок отправлен. Клиент пересчитывает от него
 * {@code freezeTick} и границы окна в своё время.</p>
 */
public record MonitorSnapshotData(
        UUID displayId,
        BlockPos rootPosition,
        ResourceLocation dimension,
        UUID loadId,
        long sequence,
        long geometryVersion,
        long observedServerTick,
        boolean linked,
        boolean selectedTargetUnavailable,
        TargetRef selectedTarget,
        List<MonitorTargetData> targets,
        List<RadarVisualProfile> visualProfiles,
        List<MonitorVisualPhase> visualPhases
) {
    public MonitorSnapshotData {
        Objects.requireNonNull(displayId, "displayId");
        rootPosition = Objects.requireNonNull(rootPosition, "rootPosition").immutable();
        Objects.requireNonNull(dimension, "dimension");
        Objects.requireNonNull(loadId, "loadId");
        if (sequence < 0 || geometryVersion < 0 || observedServerTick < 0) {
            throw new IllegalArgumentException("Monitor snapshot versions must be non-negative");
        }
        targets = List.copyOf(targets);
        visualProfiles = List.copyOf(visualProfiles);
        visualPhases = List.copyOf(visualPhases);
        if (!visualProfiles.isEmpty() && !linked) throw new IllegalArgumentException("Unlinked monitor has radar profiles");
        Set<RadarVisualProfile.Key> profileKeys = new HashSet<>();
        for (RadarVisualProfile profile : visualProfiles) {
            if (!profile.dimension().equals(dimension) || !profileKeys.add(profile.key())) {
                throw new IllegalArgumentException("Monitor snapshot profiles must be unique and same-dimension");
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
            if (!phasePositions.add(phase.radarPosition()) || visualProfiles.stream().noneMatch(profile ->
                    profile.radarPosition().equals(phase.radarPosition())
                            && phase.activeSectorIndex() < profile.sectorCount())) {
                throw new IllegalArgumentException("Monitor phase has no unique geometry profile");
            }
        }
        if (selectedTarget != null && !targetRefs.contains(selectedTarget)) {
            throw new IllegalArgumentException("A selected target must be present in this monitor snapshot");
        }
        if (selectedTarget != null && selectedTargetUnavailable) {
            throw new IllegalArgumentException("An available selected target cannot also be marked unavailable");
        }
    }

    /** Копия с тиком фактической отправки; содержимое и sequence не меняются. */
    public MonitorSnapshotData withServerTiming(long serverTick) {
        if (serverTick < 0) throw new IllegalArgumentException("Server tick must be non-negative");
        return new MonitorSnapshotData(displayId, rootPosition, dimension, loadId, sequence, geometryVersion,
                serverTick, linked, selectedTargetUnavailable, selectedTarget, targets, visualProfiles,
                visualPhases);
    }

    /** Видимое клиенту содержимое совпадает, без учёта номеров и тика отправки. */
    boolean sameContent(MonitorSnapshotData other) {
        return other != null && linked == other.linked
                && selectedTargetUnavailable == other.selectedTargetUnavailable
                && Objects.equals(selectedTarget, other.selectedTarget)
                && targets.equals(other.targets) && visualProfiles.equals(other.visualProfiles)
                && visualPhases.equals(other.visualPhases);
    }
}
