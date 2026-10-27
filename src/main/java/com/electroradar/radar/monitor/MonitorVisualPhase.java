package com.electroradar.radar.monitor;

import java.util.Objects;

import net.minecraft.core.BlockPos;

import com.electroradar.radar.visual.RadarVisualProfile;

/** Динамическая фаза одного профиля; геометрия передаётся отдельно только при baseline. */
public record MonitorVisualPhase(BlockPos radarPosition, long geometryRevision, long slotSerial,
                                 int activeSectorIndex, int sectorOrderVariant,
                                 long windowStartTick, long windowEndTick) {
    public MonitorVisualPhase {
        radarPosition = Objects.requireNonNull(radarPosition, "radarPosition").immutable();
        if (geometryRevision < 0 || slotSerial < 0 || activeSectorIndex < 0
                || activeSectorIndex >= RadarVisualProfile.MAX_SECTOR_COUNT
                || sectorOrderVariant < 1 || sectorOrderVariant > 4
                || windowStartTick < 0 || windowEndTick <= windowStartTick) {
            throw new IllegalArgumentException("Invalid monitor visual phase");
        }
    }
}
