package com.electroradar.radar.network;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class CategoryQueryTest {
    @BeforeAll static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
        SharedConstants.tryDetectVersion(); Bootstrap.bootStrap();
    }

    @Test void categoryQueryProjectsOnlyMatchingTargets() {
        var f = new Fixture();
        f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL, TargetCategory.PROJECTILE), 10);
        f.observe(f.radar, f.pig, 0, Set.of(TargetCategory.PASSIVE_MOB), 10);
        assertEquals(List.of(f.shell), f.query(TargetCategory.SHELL).targets());
        assertEquals(List.of(f.shell), f.query(TargetCategory.PROJECTILE).targets());
        assertEquals(List.of(f.pig), f.query(TargetCategory.PASSIVE_MOB).targets());
        assertEquals(Set.of(f.shell, f.pig), new HashSet<>(RadarNetworkQueries.queryTargets(
                f.consumer, f.registration, TargetView::target, 10, f.endpoints, f.networks).targets()));
    }

    @Test void categoryRemainsUntilLastSectorOfLastSourceIsRemoved() {
        var f = new Fixture();
        var categories = Set.of(TargetCategory.SHELL, TargetCategory.PROJECTILE);
        f.observe(f.radar, f.shell, 0, categories, 10);
        f.observe(f.radar, f.shell, 1, categories, 10);
        f.observe(f.otherRadar, f.shell, 0, categories, 10);
        var snapshot = f.query(TargetCategory.SHELL);
        f.remove(f.radar, f.shell, 0);
        f.remove(f.radar, f.shell, 1);
        assertEquals(List.of(f.shell), f.query(TargetCategory.SHELL).targets());
        f.remove(f.otherRadar, f.shell, 0);
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        assertTrue(f.query(TargetCategory.PROJECTILE).targets().isEmpty());
        assertEquals(List.of(f.shell), snapshot.targets(), "Snapshots must survive later mutations");
    }

    @Test void categoryQuerySelectsOnlyMeasurementsAndSourcesThatConfirmedThatCategory() {
        var f = new Fixture();
        f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL), 10);
        f.observe(f.radar, f.shell, 1, Set.of(TargetCategory.PASSIVE_MOB), 20);
        f.observe(f.otherRadar, f.shell, 0, Set.of(TargetCategory.PASSIVE_MOB), 30);
        record Read(long tick, List<EndpointKey> sources) { }
        var samples = RadarNetworkQueries.queryTargets(f.consumer, f.registration, TargetCategory.SHELL,
                view -> new Read(view.liveResult().sampledTick(), view.sources().stream().map(RadarSource::radar).toList()),
                30, f.endpoints, f.networks);
        assertEquals(List.of(new Read(10, List.of(f.radar))), samples.targets());
    }

    @Test void frozenCategoryExpiresAtItsOriginalDeadlineWithoutRemovingOtherSectors() {
        var f = new Fixture();
        var shell = f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL), 10);
        f.observe(f.radar, f.shell, 1, Set.of(TargetCategory.PROJECTILE), 12);
        shell.measurement = shell.measurement.frozen(11, 70);
        var state = f.network.radars.get(f.radar);
        assertEquals(0, state.forgetFadedConfirmations(f.shell, 80));
        var remembered = RadarNetworkQueries.queryTargets(f.consumer, f.registration, TargetCategory.SHELL,
                TargetView::liveResult, 80, f.endpoints, f.networks).targets().getFirst();
        assertFalse(remembered.observing());
        assertEquals(10, remembered.sampledTick());
        assertEquals(1, state.forgetFadedConfirmations(f.shell, 81));
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        assertEquals(List.of(f.shell), f.query(TargetCategory.PROJECTILE).targets());
    }

    @Test void changingOnlyCategoryInvalidatesVisibleContentWhileSampleTimeAloneDoesNot() {
        var f = new Fixture();
        var confirmation = f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL), 10);
        var state = f.network.radars.get(f.radar);
        var sample = confirmation.measurement;
        assertTrue(state.publishMeasurement(f.shell, confirmation, sample, Set.of(TargetCategory.PASSIVE_MOB)));
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        assertEquals(List.of(f.shell), f.query(TargetCategory.PASSIVE_MOB).targets());
        assertFalse(state.publishMeasurement(f.shell, confirmation, LiveResult.observed(20,
                sample.position().orElseThrow(), sample.entityType().orElseThrow()), Set.of(TargetCategory.PASSIVE_MOB)));
        var samples = RadarNetworkQueries.queryTargets(f.consumer, f.registration, TargetCategory.PASSIVE_MOB,
                TargetView::liveResult, 20, f.endpoints, f.networks);
        assertEquals(20, samples.targets().getFirst().sampledTick());
    }

    @Test void hiddenSourceCategoryCannotBeReadThroughAnotherAccessibleSource() {
        var f = new Fixture();
        f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.PASSIVE_MOB), 10);
        f.observe(f.otherRadar, f.shell, 0, Set.of(TargetCategory.SHELL), 20);
        var removed = f.endpoints.remove(f.otherRadar);
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        assertEquals(List.of(f.shell), f.query(TargetCategory.PASSIVE_MOB).targets());
        // A new endpoint registration does not authenticate the old radar runtime/index.
        f.endpoints.put(f.otherRadar, new RadarNetworkState.Registration(f.id, EndpointRole.RADAR, RegistrationToken.create()));
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        f.endpoints.put(f.otherRadar, new RadarNetworkState.Registration(new NetworkId(new UUID(0,999)),
                EndpointRole.RADAR, removed.token));
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
    }

    @Test void newRadarRuntimeStartsWithoutCategoriesFromPreviousRegistration() {
        var f = new Fixture();
        f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL), 10);
        f.register(f.radar);
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.PASSIVE_MOB), 20);
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
        assertEquals(List.of(f.shell), f.query(TargetCategory.PASSIVE_MOB).targets());
    }

    @Test void categoriesDoNotCrossDimensions() {
        var f = new Fixture();
        var nether = ResourceKey.create(Registries.DIMENSION, ResourceLocation.withDefaultNamespace("the_nether"));
        var foreign = new TargetRef(nether, TargetRef.SourceKind.ENTITY, f.shell.id());
        f.observe(f.radar, foreign, 0, Set.of(TargetCategory.SHELL), 10);
        assertTrue(f.query(TargetCategory.SHELL).targets().isEmpty());
    }

    @Test void unavailableSampleKeepsItsIdentityAndDoesNotInventCoordinates() {
        var f = new Fixture();
        var confirmation = f.observe(f.radar, f.shell, 0, Set.of(TargetCategory.SHELL), 10);
        confirmation.measurement = LiveResult.unavailable(11, -1, 0);
        var samples = RadarNetworkQueries.queryTargets(f.consumer, f.registration, TargetCategory.SHELL,
                TargetView::liveResult, 11, f.endpoints, f.networks);
        assertEquals(List.of(confirmation.measurement), samples.targets());
        assertTrue(samples.targets().getFirst().position().isEmpty());
    }

    @Test void incrementalCategoryQueriesMatchIndependentMembershipHistory() {
        var f = new Fixture();
        record Confirmation(EndpointKey radar, TargetRef target, int sector) { }
        Map<Confirmation, Set<TargetCategory>> history = new HashMap<>();
        var otherRegistration = f.endpoints.get(f.otherRadar);
        var choices = List.of(Set.of(TargetCategory.SHELL, TargetCategory.PROJECTILE),
                Set.of(TargetCategory.PROJECTILE), Set.of(TargetCategory.PASSIVE_MOB),
                Set.of(TargetCategory.OTHER), Set.<TargetCategory>of());
        Random random = new Random(221);
        for (int event=0; event<2000; event++) {
            var endpoint = random.nextBoolean() ? f.radar : f.otherRadar;
            var target = new TargetRef(f.world, TargetRef.SourceKind.ENTITY, new UUID(0, 10 + random.nextInt(30)));
            int sector = random.nextInt(3);
            var key = new Confirmation(endpoint, target, sector);
            if (random.nextInt(4) == 0) {
                f.remove(endpoint, target, sector);
                history.remove(key);
            } else {
                var categories = choices.get(random.nextInt(choices.size()));
                f.observe(endpoint, target, sector, categories, event);
                history.put(key, categories);
            }
            if (event % 25 == 0) {
                if (f.endpoints.containsKey(f.otherRadar)) f.endpoints.remove(f.otherRadar);
                else f.endpoints.put(f.otherRadar, otherRegistration);
            }
            for (TargetCategory category : TargetCategory.values()) {
                Set<TargetRef> expected = new HashSet<>();
                history.forEach((confirmation, categories) -> {
                    if (f.endpoints.containsKey(confirmation.radar()) && categories.contains(category)) {
                        expected.add(confirmation.target());
                    }
                });
                assertEquals(expected, new HashSet<>(f.query(category).targets()));
            }
        }
    }

    static final class Fixture {
        final ResourceKey<Level> world = ResourceKey.create(Registries.DIMENSION,
                ResourceLocation.withDefaultNamespace("overworld"));
        final NetworkId id = new NetworkId(new UUID(0, 1));
        final EndpointKey consumer = new EndpointKey.Static(world, new BlockPos(-1, 0, 0));
        final EndpointKey radar = new EndpointKey.Static(world, BlockPos.ZERO);
        final EndpointKey otherRadar = new EndpointKey.Static(world, new BlockPos(1, 0, 0));
        final TargetRef shell = new TargetRef(world, TargetRef.SourceKind.ENTITY, new UUID(0, 2));
        final TargetRef pig = new TargetRef(world, TargetRef.SourceKind.ENTITY, new UUID(0, 3));
        final RadarNetworkState.Registration registration = new RadarNetworkState.Registration(
                id, EndpointRole.CONSUMER, RegistrationToken.create());
        final RadarNetworkState.NetworkRuntime network = new RadarNetworkState.NetworkRuntime(id);
        final Map<NetworkId, RadarNetworkState.NetworkRuntime> networks = Map.of(id, network);
        final Map<EndpointKey, RadarNetworkState.Registration> endpoints = new HashMap<>();
        Fixture() {
            endpoints.put(consumer, registration);
            register(radar); register(otherRadar);
        }
        void register(EndpointKey endpoint) {
            var reg = new RadarNetworkState.Registration(id, EndpointRole.RADAR, RegistrationToken.create());
            endpoints.put(endpoint, reg);
            network.radars.put(endpoint, new RadarNetworkState.RadarState(reg.token, 0, null));
        }
        RadarNetworkState.SectorConfirmation observe(EndpointKey endpoint, TargetRef target, int sector,
                                                      Set<TargetCategory> categories, long tick) {
            var state = network.radars.get(endpoint);
            var link = state.detections.computeIfAbsent(target, unused -> new RadarNetworkState.SourceLink());
            network.targets.computeIfAbsent(target, unused -> new RadarNetworkState.TargetEntry()).sources.put(endpoint, link);
            var confirmation = link.sectors.computeIfAbsent(sector, unused -> new RadarNetworkState.SectorConfirmation());
            state.publishMeasurement(target, confirmation, LiveResult.observed(tick, new Vec3(tick,64,0), EntityType.PIG), categories);
            confirmation.lastSuccessfulVisitTick = tick;
            return confirmation;
        }
        TargetSnapshot<TargetRef> query(TargetCategory category) {
            return RadarNetworkQueries.queryTargets(consumer, registration, category, TargetView::target,
                    10, endpoints, networks);
        }
        void remove(EndpointKey endpoint, TargetRef target, int sector) {
            var state = network.radars.get(endpoint);
            var link = state.detections.get(target);
            if (!state.removeConfirmation(target, sector) || !link.sectors.isEmpty()) return;
            state.detections.remove(target);
            var entry = network.targets.get(target);
            entry.sources.remove(endpoint);
            if (entry.sources.isEmpty()) network.targets.remove(target);
        }
    }
}
