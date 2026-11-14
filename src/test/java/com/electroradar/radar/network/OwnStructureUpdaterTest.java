package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.spatial.*;

class OwnStructureUpdaterTest {
    @Test void severalRadarsOnOneShipPublishOneFreshSamplePerTick() {
        var f = new Fixture();
        AtomicInteger reads = new AtomicInteger();
        assertTrue(OwnStructureUpdater.refresh(f.network, f.endpoints, 10, moving -> {
            reads.incrementAndGet(); return Optional.of(f.body);
        }));
        assertEquals(1, reads.get());
        assertEquals(1, f.network.ownStructures.size());
        assertFalse(OwnStructureUpdater.refresh(f.network, f.endpoints, 11, moving -> Optional.of(f.body)));
        assertEquals(11, f.network.ownStructures.get(f.target).sampledTick());
    }

    @Test void losingOneRadarPreservesVisibilityAndLosingLastRadarRemovesIt() {
        var f = new Fixture();
        f.refresh(10);
        f.endpoints.remove(f.first);
        assertFalse(f.refresh(11));
        assertEquals(11, f.network.ownStructures.get(f.target).sampledTick());
        f.endpoints.remove(f.second);
        assertTrue(f.refresh(12));
        assertTrue(f.network.ownStructures.isEmpty());
        assertFalse(f.refresh(13));
    }

    @Test void replacedTokenAndForeignNetworkCannotRefreshOldOwnership() {
        var f = new Fixture();
        f.refresh(10);
        var first = f.endpoints.get(f.first);
        f.endpoints.put(f.first, new RadarNetworkState.Registration(f.network.id, EndpointRole.RADAR, RegistrationToken.create()));
        f.endpoints.put(f.second, new RadarNetworkState.Registration(new NetworkId(new UUID(0,99)), EndpointRole.RADAR,
                f.endpoints.get(f.second).token));
        assertTrue(f.refresh(11));
        assertTrue(f.network.ownStructures.isEmpty());
        f.endpoints.put(f.first, first);
        assertTrue(f.refresh(12));
    }

    @Test void missingGeometryRevokesOwnSampleImmediately() {
        var f = new Fixture(); f.refresh(10);
        assertTrue(OwnStructureUpdater.refresh(f.network, f.endpoints, 11, moving -> Optional.empty()));
        assertTrue(f.network.ownStructures.isEmpty());
    }

    @Test void changedPoseIsVisibleWithoutWaitingForRadarMeasurementSchedule() {
        var f = new Fixture(); f.refresh(10);
        var moved = new StructureBody(f.target, List.of(), new Vec3(25,64,0),
                new StructureMeasurement("ship", Silhouette.EMPTY, new Vec3(25,64,0), new Vec3(1,0,0), 1));
        assertTrue(OwnStructureUpdater.refresh(f.network, f.endpoints, 11, moving -> Optional.of(moved)));
        assertEquals(moved.marker(), f.network.ownStructures.get(f.target).position().orElseThrow());
    }

    static final class Fixture {
        final ResourceKey<Level> world = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("overworld"));
        final TargetRef target = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0,1));
        final RadarNetworkState.NetworkRuntime network = new RadarNetworkState.NetworkRuntime(new NetworkId(new UUID(0,2)));
        final EndpointKey.Moving first = new EndpointKey.Moving(world, target.id(), BlockPos.ZERO);
        final EndpointKey.Moving second = new EndpointKey.Moving(world, target.id(), new BlockPos(1,0,0));
        final Map<EndpointKey, RadarNetworkState.Registration> endpoints = new HashMap<>();
        final StructureBody body = new StructureBody(target, List.of(), new Vec3(0,64,0),
                new StructureMeasurement("ship", Silhouette.EMPTY, new Vec3(0,64,0), Vec3.ZERO, 1));
        Fixture() {
            for (var endpoint : List.of(first,second)) {
                var registration = new RadarNetworkState.Registration(network.id, EndpointRole.RADAR, RegistrationToken.create());
                endpoints.put(endpoint, registration);
                network.radars.put(endpoint, new RadarNetworkState.RadarState(registration.token,0,null));
            }
        }
        boolean refresh(long tick) {
            return OwnStructureUpdater.refresh(network, endpoints, tick, moving -> Optional.of(body));
        }
    }
}
