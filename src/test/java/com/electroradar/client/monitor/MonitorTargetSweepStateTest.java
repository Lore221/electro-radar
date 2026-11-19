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

    @Test void rotatingBeamLatchesLatestPointAndFadesForSeventyTicks() {
        var radar = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(0, 0));
        var state = new MonitorTargetSweepState();
        state.accept(snapshot(target(40, false, Optional.empty()), 1), 0);
        assertTrue(state.targets(KEY, rotating(radar), 0).isEmpty());
        var latched = state.targets(KEY, rotating(radar), 20).getFirst();
        assertEquals(40, latched.x());
        assertEquals(18.75, latched.rememberedFadeStartClientTick(), 1e-8);
        state.accept(snapshot(target(60, false, Optional.empty()), 2), 21);
        var frozen = state.targets(KEY, rotating(radar), 21).getFirst();
        assertEquals(40, frozen.x());
        assertEquals(0.5, frozen.rememberedOpacity(53.75), 1e-6);
        assertEquals(0, frozen.rememberedOpacity(88.75));
        assertEquals(60, state.targets(KEY, rotating(radar), 95).getFirst().x());
    }

    @Test void rotatingProjectionEmitsOnlyOutlineAndNorthClockwiseRadius() {
        var radar = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(1000, 0));
        var frame = new MonitorSectorAnimationState.ProfileFrame(radar, List.of(), Map.of(), 0,
                Double.POSITIVE_INFINITY, 900, 118.75);
        var projected = MonitorSectorProjection.project(radar, frame, DIMENSION, 0, 0,
                com.electroradar.radar.monitor.MapFacing.of(net.minecraft.core.Direction.NORTH), 0, 0, 200);
        assertTrue(projected.sectors().isEmpty());
        assertEquals(1, projected.separators().size());
        var radius = projected.separators().getFirst();
        assertEquals(0, radius.start().x(), 1e-8);
        assertEquals(1, radius.end().x(), 1e-8);
        assertEquals(0, radius.end().y(), 1e-8);
    }

    @Test void delayedSnapshotMapsManagerEpochToClientClockAndWrapsWithoutResetOnOpening() {
        MonitorSectorAnimationState.clear();
        var radar = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(1000, 350));
        var snapshot = new MonitorSnapshotData(KEY.displayId(), ROOT.position(), DIMENSION, KEY.loadId(),
                1, 1, 1005, true, false, null, List.of(), List.of(radar), List.of(), ROOT);
        MonitorSectorAnimationState.accept(KEY, snapshot, 200);
        var frame = MonitorSectorAnimationState.visualsAt(KEY, 200).getFirst();
        assertEquals(14, MonitorRotatingSweep.angle(frame, 200), 1e-8);
        assertEquals(62, MonitorRotatingSweep.angle(MonitorSectorAnimationState.visualsAt(KEY, 210).getFirst(), 210), 1e-8);
        assertEquals(14, MonitorRotatingSweep.angle(MonitorSectorAnimationState.visualsAt(KEY, 275).getFirst(), 275), 1e-8);
        MonitorSectorAnimationState.clear();
    }

    @Test void anotherIndependentRadarCanLatchButOwnMovingSourceCannot() {
        var first = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(0, 0));
        var own = first.withSource(new EndpointKey.Moving(WORLD, TARGET.id(), BlockPos.ZERO));
        var state = new MonitorTargetSweepState();
        state.accept(snapshot(target(40, false, Optional.empty()), 1), 0);
        state.targets(KEY, rotating(own), 0);
        assertTrue(state.targets(KEY, rotating(own), 20).isEmpty());
        var second = first.withSource(new EndpointKey.Static(WORLD, new BlockPos(2, 0, 0)))
                .withRotation(new com.electroradar.radar.simple.RadarRotation(0, 90));
        var both = new java.util.ArrayList<>(rotating(first));
        both.addAll(rotating(second));
        assertEquals(40, state.targets(KEY, both, 75).getFirst().x());
        assertEquals(75, state.targets(KEY, both, 75).getFirst().rememberedFadeStartClientTick(), 1e-8);
    }

    @Test void rotatingCenterPointWaitsForItsNextPassAndHighPointsStayHidden() {
        var radar = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(0, 0));
        var state = new MonitorTargetSweepState();
        state.accept(snapshot(target(0, false, Optional.empty()), 1), 0);
        assertEquals(0, state.targets(KEY, rotating(radar), 0).getFirst().rememberedFadeStartClientTick());
        assertEquals(0, state.targets(KEY, rotating(radar), 10).getFirst().rememberedFadeStartClientTick());
        state = new MonitorTargetSweepState();
        var high = new MonitorTargetData(TARGET, true, true, 40, 85, 0, "", -1, 0, Silhouette.EMPTY,
                false, Optional.empty());
        state.accept(snapshot(high, 1), 0);
        state.targets(KEY, rotating(radar), 0);
        assertTrue(state.targets(KEY, rotating(radar), 20).isEmpty());
    }

    @Test void rotatingCoarseContactUsesAcceptedAnchorAndFreezesActualCenter() {
        var radar = RADAR.withRotation(new com.electroradar.radar.simple.RadarRotation(0, 0));
        var state = new MonitorTargetSweepState();
        var coarse = new MonitorTargetData(TARGET, true, true, 110, 100, 0, "", -1, 0, Silhouette.EMPTY,
                false, Optional.of(new ScanAnchor(radar.source(), new Point(40, 0))));
        state.accept(snapshot(coarse, 1), 0);
        assertTrue(state.targets(KEY, rotating(radar), 0).isEmpty());
        var shown = state.targets(KEY, rotating(radar), 20).getFirst();
        assertEquals(110, shown.x());
        assertEquals(100, shown.y());
        assertEquals(18.75, shown.rememberedFadeStartClientTick(), 1e-8);
    }

    private static List<MonitorSectorAnimationState.ProfileFrame> rotating(RadarVisualProfile radar) {
        return List.of(new MonitorSectorAnimationState.ProfileFrame(radar, List.of(), Map.of(), 0,
                Double.POSITIVE_INFINITY));
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
