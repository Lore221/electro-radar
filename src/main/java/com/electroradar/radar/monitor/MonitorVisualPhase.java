package com.electroradar.radar.monitor;

import java.util.Objects;
import java.util.HashSet;
import java.util.List;

import net.minecraft.core.BlockPos;

import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Текущее посещение одного радара: какой сектор активен и в каком окне серверных тиков.
 * Начало окна однозначно отличает одно посещение от следующего, отдельный номер слота не нужен.
 */
public record MonitorVisualPhase(BlockPos radarPosition, List<Integer> activeSectorIndices,
                                 long windowStartTick, long windowEndTick) {
    public MonitorVisualPhase {
        radarPosition = Objects.requireNonNull(radarPosition, "radarPosition").immutable();
        activeSectorIndices = List.copyOf(activeSectorIndices);
        if (activeSectorIndices.isEmpty() || activeSectorIndices.size() > RadarVisualProfile.MAX_SECTOR_COUNT
                || activeSectorIndices.stream().anyMatch(index -> index < 0
                        || index >= RadarVisualProfile.MAX_SECTOR_COUNT)
                || new HashSet<>(activeSectorIndices).size() != activeSectorIndices.size()
                || windowStartTick < 0 || windowEndTick <= windowStartTick) {
            throw new IllegalArgumentException("Invalid monitor visual phase");
        }
    }

    public MonitorVisualPhase(BlockPos radarPosition, int activeSectorIndex,
                              long windowStartTick, long windowEndTick) {
        this(radarPosition, List.of(activeSectorIndex), windowStartTick, windowEndTick);
    }
}
