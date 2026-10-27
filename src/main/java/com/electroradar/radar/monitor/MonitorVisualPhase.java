package com.electroradar.radar.monitor;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import com.electroradar.radar.visual.RadarVisualProfile;

/**
 * Текущее посещение одного радара: какой сектор активен и в каком окне серверных тиков.
 * Начало окна однозначно отличает одно посещение от следующего, отдельный номер слота не нужен.
 */
public record MonitorVisualPhase(BlockPos radarPosition, int activeSectorIndex,
                                 long windowStartTick, long windowEndTick) {
    public MonitorVisualPhase {
        radarPosition = Objects.requireNonNull(radarPosition, "radarPosition").immutable();
        if (activeSectorIndex < 0 || activeSectorIndex >= RadarVisualProfile.MAX_SECTOR_COUNT
                || windowStartTick < 0 || windowEndTick <= windowStartTick) {
            throw new IllegalArgumentException("Invalid monitor visual phase");
        }
    }
}
