package com.electroradar.client.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.spatial.ScanAnchor;
import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.visual.RadarVisualFadeStyle;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.visual.RadarVisualProfile;

class MonitorTargetSweepStateTest {
    private static final ResourceLocation DIMENSION = ResourceLocation.withDefaultNamespace("overworld");
    private static final ResourceKey<net.minecraft.world.level.Level> WORLD = ResourceKey.create(Registries.DIMENSION, DIMENSION);
    private static final EndpointKey.Static ROOT = new EndpointKey.Static(WORLD, new BlockPos(0, 64, 0));
    private static final MonitorDisplayKey KEY = new MonitorDisplayKey(ROOT, new UUID(0, 1), new UUID(0, 2));
    private static final TargetRef TARGET = new TargetRef(WORLD, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 3));
    private static final RadarVisualProfile RADAR = RadarVisualProfile.triangularFan(DIMENSION, BlockPos.ZERO,
            0, 64, 0, 100, 0, RadarVisualProfile.FULL_TURN, 4, 1, RadarVisualFadeStyle.SECTOR);

    @Test void ownStructureIsVisibleWithoutAnyRadarAndTracksNewSnapshots() {
        var state = new MonitorTargetSweepState();
        state.accept(snapshot(target(110, true, Optional.empty()), 1), 0);
        var shown = state.targets(KEY, List.of(), 0);
        assertEquals(1, shown.size());
        assertEquals(110, shown.getFirst().x());
        assertFalse(shown.getFirst().rememberedPoint());
        state.accept(snapshot(target(120, true, Optional.empty()), 2), 1);
        assertEquals(120, state.targets(KEY, List.of(), 1).getFirst().x());
        state.accept(snapshot(null, 3), 2);
        assertTrue(state.targets(KEY, List.of(), 2).isEmpty(), "revoked access removes the own-structure marker");
    }

    @Test void edgeDetectionUsesPhysicalScanAnchorAndKeepsSilhouetteCenter() {
        var state = new MonitorTargetSweepState();
        var anchor = new ScanAnchor(RADAR.source(), new Point(20, -20));
        state.accept(snapshot(target(110, false, Optional.of(anchor)), 1), 0);
        var shown = state.targets(KEY, frames(RADAR, 0, 10), 0);
        assertEquals(1, shown.size(), "hitbox intersects coverage even though marker center is outside");
        assertEquals(110, shown.getFirst().x(), "selection stays at the silhouette center");
        var frozen = state.targets(KEY, frames(RADAR, 1, 20), 10).getFirst();
        assertTrue(frozen.rememberedPoint());
        assertEquals(10, frozen.rememberedFadeStartClientTick());
    }

    @Test void anotherRadarCannotLightAnAnchorItDidNotObserve() {
        var state = new MonitorTargetSweepState();
        var anchor = new ScanAnchor(new EndpointKey.Static(WORLD, new BlockPos(1, 0, 0)), new Point(20, -20));
        state.accept(snapshot(target(110, false, Optional.of(anchor)), 1), 0);
        assertTrue(state.targets(KEY, frames(RADAR, 0, 10), 0).isEmpty());
    }

    @Test void foreignStructureStillWaitsForItsSubsector() {
        var state = new MonitorTargetSweepState();
        state.accept(snapshot(target(110, false, Optional.of(new ScanAnchor(RADAR.source(), new Point(20, -20)))), 1), 0);
        assertTrue(state.targets(KEY, frames(RADAR, 1, 10), 0).isEmpty());
    }

    private static MonitorTargetData target(double x, boolean own, Optional<ScanAnchor> anchor) {
        return new MonitorTargetData(TARGET, true, true, x, 64, 0, "", -1, 0, Silhouette.EMPTY, own, anchor);
    }

    private static MonitorSnapshotData snapshot(MonitorTargetData target, long sequence) {
        return new MonitorSnapshotData(KEY.displayId(), ROOT.position(), DIMENSION, KEY.loadId(), sequence, 0, sequence,
                true, false, null, target == null ? List.of() : List.of(target), List.of(), List.of(), ROOT);
    }

    private static List<MonitorSectorAnimationState.ProfileFrame> frames(RadarVisualProfile radar, int sector, double end) {
        return List.of(new MonitorSectorAnimationState.ProfileFrame(radar, List.of(sector), Map.of(), 0, end));
    }
}
