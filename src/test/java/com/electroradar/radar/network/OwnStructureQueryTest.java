package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.spatial.StructureBody;
import com.electroradar.radar.spatial.StructureMeasurement;
import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.spatial.ScanAnchor;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;

class OwnStructureQueryTest {
    @Test void networkMonitorGrantsOwnStructureVisibilityWithoutRadarAndRevokesItOnUnregister() {
        var world = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        var own = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 1));
        var endpoint = new EndpointKey.Moving(world, own.id(), BlockPos.ZERO);
        var id = new NetworkId(new UUID(0, 2));
        var registration = new RadarNetworkState.Registration(id, EndpointRole.MONITOR, RegistrationToken.create());
        var network = new RadarNetworkState.NetworkRuntime(id);
        var measurement = new StructureMeasurement("own", Silhouette.EMPTY, Vec3.ZERO, Vec3.ZERO, 1);
        var body = new StructureBody(own, List.of(), Vec3.ZERO, measurement);
        network.ownStructures.put(own, LiveResult.observedStructure(10, body));
        var networks = Map.of(id, network);
        Map<EndpointKey, RadarNetworkState.Registration> index = Map.of(endpoint, registration);
        var visible = RadarNetworkQueries.queryTargets(endpoint, registration, view -> {
            assertTrue(view.sources().isEmpty());
            assertTrue(view.observingNow());
            return view.registeredStructure();
        }, 10, index, networks);
        assertEquals(List.of(true), visible.targets());
        assertTrue(RadarNetworkQueries.queryTargets(endpoint, registration, TargetView::target,
                11, Map.of(), networks).targets().isEmpty());
    }

    @Test void serverMemoryFreezesPhysicalAnchorTogetherWithSilhouette() {
        var world = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        var target = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 1));
        var radar = new EndpointKey.Static(world, BlockPos.ZERO);
        var body = new StructureBody(target, List.of(), new Vec3(110, 64, 0),
                new StructureMeasurement("foreign", Silhouette.EMPTY, Vec3.ZERO, new Vec3(1, 0, 0), 1));
        var anchor = new ScanAnchor(radar, new Point(90, 0));
        var observed = LiveResult.observedStructure(10, body, anchor);
        var frozen = observed.frozen(11, 70);
        assertEquals(observed.position(), frozen.position());
        assertEquals(observed.structure(), frozen.structure());
        assertEquals(anchor, frozen.structure().orElseThrow().scanAnchor().orElseThrow());
        assertFalse(frozen.observing());
        assertEquals(10, frozen.sampledTick());
        assertEquals(11, frozen.freezeTick());
    }
}
