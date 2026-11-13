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
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.spatial.StructureBody;
import com.electroradar.radar.spatial.StructureMeasurement;
import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.spatial.ScanAnchor;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;

class OwnStructureQueryTest {
    @Test void networkMonitorDoesNotGrantOwnStructureVisibilityWithoutRadarDetection() {
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
        var visible = RadarNetworkQueries.queryTargets(endpoint, registration, TargetView::target,
                10, index, networks);
        assertTrue(visible.targets().isEmpty(), "A connected monitor must not reveal an undetected structure");
        assertTrue(RadarNetworkQueries.queryTargets(endpoint, registration, TargetView::target,
                11, Map.of(), networks).targets().isEmpty());
    }

    @Test void monitorCannotReadUndetectedStructureEvenWhenItsNetworkHasARadar() {
        var fixture = new StructureFixture();
        assertTrue(fixture.query().targets().isEmpty());
        assertTrue(RadarNetworkQueries.queryTargetRefs(fixture.monitor, fixture.monitorRegistration, 10,
                fixture.index, fixture.networks).targets().isEmpty());
        assertTrue(RadarNetworkQueries.queryTargetDetails(fixture.monitor, fixture.monitorRegistration,
                List.of(fixture.structure), TargetView::target, 10, fixture.index, fixture.networks).targets().isEmpty());
        var sources = RadarNetworkQueries.sourcesForTarget(fixture.monitor, fixture.monitorRegistration,
                fixture.structure, 10, fixture.index, fixture.networks);
        assertFalse(sources.confirmed());
        assertTrue(sources.liveResult().isEmpty());
    }

    @Test void radarDetectionRevealsMonitorStructureWithItsRealSourceAndSample() {
        var fixture = new StructureFixture();
        var link = new RadarNetworkState.SourceLink();
        var confirmation = new RadarNetworkState.SectorConfirmation();
        var observed = fixture.network.ownStructures.get(fixture.structure);
        confirmation.measurement = observed;
        confirmation.lastSuccessfulVisitTick = 10;
        link.sectors.put(0, confirmation);
        fixture.radarState.detections.put(fixture.structure, link);
        var target = new RadarNetworkState.TargetEntry();
        target.sources.put(fixture.radar, link);
        fixture.network.targets.put(fixture.structure, target);
        var samples = RadarNetworkQueries.queryTargets(fixture.monitor, fixture.monitorRegistration, view -> {
            assertFalse(view.registeredStructure());
            assertEquals(1, view.sources().size());
            return view.liveResult();
        }, 10, fixture.index, fixture.networks);
        assertEquals(List.of(observed), samples.targets());
        var sources = RadarNetworkQueries.sourcesForTarget(fixture.monitor, fixture.monitorRegistration,
                fixture.structure, 10, fixture.index, fixture.networks);
        assertTrue(sources.confirmed());
        assertFalse(sources.registeredStructure());
        assertEquals(List.of(fixture.structure), fixture.query().targets());
        var frozen = observed.frozen(11, 70);
        confirmation.measurement = frozen;
        assertEquals(List.of(frozen), RadarNetworkQueries.queryTargets(fixture.monitor, fixture.monitorRegistration,
                TargetView::liveResult, 11, fixture.index, fixture.networks).targets());
        assertTrue(RadarNetworkQueries.queryTargets(fixture.monitor, fixture.monitorRegistration, TargetView::target,
                11, Map.of(fixture.monitor, fixture.monitorRegistration), fixture.networks).targets().isEmpty());
    }

    @Test void radarOnStructureStillProvidesOwnPlacementVisibility() {
        var fixture = new StructureFixture();
        var movingRadar = new EndpointKey.Moving(fixture.world, fixture.structure.id(), new BlockPos(1, 0, 0));
        assertEquals(List.of(fixture.structure), RadarNetworkQueries.queryTargets(fixture.monitor,
                fixture.monitorRegistration, TargetView::target, 10,
                Map.of(fixture.monitor, fixture.monitorRegistration, movingRadar, fixture.radarRegistration),
                fixture.networks).targets());
    }

    private static final class StructureFixture {
        final ResourceKey<Level> world =
                ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        final TargetRef structure = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 1));
        final EndpointKey monitor = new EndpointKey.Moving(world, structure.id(), BlockPos.ZERO);
        final EndpointKey radar = new EndpointKey.Static(world, BlockPos.ZERO);
        final NetworkId id = new NetworkId(new UUID(0, 2));
        final RadarNetworkState.Registration monitorRegistration =
                new RadarNetworkState.Registration(id, EndpointRole.MONITOR, RegistrationToken.create());
        final RadarNetworkState.Registration radarRegistration =
                new RadarNetworkState.Registration(id, EndpointRole.RADAR, RegistrationToken.create());
        final RadarNetworkState.RadarState radarState = new RadarNetworkState.RadarState(radarRegistration.token, 0, null);
        final RadarNetworkState.NetworkRuntime network = new RadarNetworkState.NetworkRuntime(id);
        final Map<NetworkId, RadarNetworkState.NetworkRuntime> networks = Map.of(id, network);
        final Map<EndpointKey, RadarNetworkState.Registration> index =
                Map.of(monitor, monitorRegistration, radar, radarRegistration);

        StructureFixture() {
            network.radars.put(radar, radarState);
            var body = new StructureBody(structure, List.of(), new Vec3(1000, 64, 0),
                    new StructureMeasurement("monitor structure", Silhouette.EMPTY, Vec3.ZERO, Vec3.ZERO, 1));
            // Even an already populated placement cache must not bypass detection permissions.
            network.ownStructures.put(structure, LiveResult.observedStructure(10, body));
        }

        TargetSnapshot<TargetRef> query() {
            return RadarNetworkQueries.queryTargets(monitor, monitorRegistration, TargetView::target, 10, index, networks);
        }
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
