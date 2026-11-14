package com.electroradar.radar.monitor.network;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import com.electroradar.radar.monitor.MonitorSnapshotData;
import com.electroradar.radar.monitor.MonitorTargetData;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.TargetRef;
import com.electroradar.radar.spatial.ScanAnchor;
import com.electroradar.radar.spatial.Silhouette;
import com.electroradar.radar.visual.RadarVisualGeometry.Point;
import com.electroradar.radar.visual.RadarVisualProfile;
import com.electroradar.radar.monitor.MonitorVisualPhase;

class MonitorSnapshotPayloadTest {
    private static MonitorSnapshotData multipartSnapshot(UUID display, long tick) {
        var dimension = ResourceLocation.withDefaultNamespace("overworld");
        var world = ResourceKey.create(Registries.DIMENSION, dimension);
        var endpoint = new EndpointKey.Static(world, BlockPos.ZERO);
        var targets = java.util.stream.IntStream.range(0, 194).mapToObj(i ->
                new MonitorTargetData(new TargetRef(world, TargetRef.SourceKind.ENTITY, new UUID(0, i + 1)),
                        true, true, i, 64, -i, "minecraft:pig", -1, 0)).toList();
        var profiles = java.util.stream.IntStream.range(0, 9).mapToObj(i ->
                RadarVisualProfile.rectangle(dimension, new BlockPos(i, 64, 0), i, 64, 0, 100, 100, 2)).toList();
        var phases = profiles.stream().map(p -> new MonitorVisualPhase(p.radarPosition(),
                List.of(0), 1, 8, p.source())).toList();
        return new MonitorSnapshotData(display, BlockPos.ZERO, dimension, new UUID(0, 500), 3, 2, tick,
                true, false, targets.getFirst().target(), targets, profiles, phases, endpoint);
    }

    @Test void multipartWireFormatRemainsCompatible() throws Exception {
        var snapshot = multipartSnapshot(new UUID(0, 1000), 7);
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (boolean geometry : new boolean[]{true, false}) {
            var parts = MonitorSnapshotPayload.split(snapshot, geometry);
            assertEquals(2, parts.size());
            for (var part : parts) {
                var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
                try {
                    MonitorSnapshotPayload.STREAM_CODEC.encode(buffer, part);
                    byte[] bytes = new byte[buffer.readableBytes()];
                    buffer.getBytes(buffer.readerIndex(), bytes);
                    digest.update(bytes);
                    assertEquals(part, MonitorSnapshotPayload.STREAM_CODEC.decode(buffer));
                    assertFalse(buffer.isReadable());
                } finally { buffer.release(); }
            }
        }
        // Captured from the v9 encoder before sharing packet bodies between displays.
        assertEquals("44ac1156e78130d47b326c92d5c8cf3bbf7eadb8bf3e9e0a56e9b5b636817ce0",
                java.util.HexFormat.of().formatHex(digest.digest()));
    }

    @Test void sharedPreparationReusesBodiesWithoutSharingDisplayHeaders() {
        var first = multipartSnapshot(new UUID(0, 1000), 7);
        var second = new MonitorSnapshotData(new UUID(0, 1001), new BlockPos(32, 64, 0), first.dimension(),
                new UUID(0, 600), 10, 5, 21, true, false, first.targets().getLast().target(),
                first.targets(), first.visualProfiles(), first.visualPhases(),
                new EndpointKey.Static(first.rootEndpoint().dimension(), new BlockPos(32, 64, 0)));
        var prepared = MonitorSnapshotPayload.prepare(first.targets(), first.visualProfiles(), first.visualPhases());
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        for (boolean geometry : new boolean[]{true, false}) {
            var before = prepared.address(first, geometry);
            var after = prepared.address(second, geometry);
            assertEquals(before.size(), after.size());
            for (int index = 0; index < before.size(); index++) {
                assertSame(before.get(index).body(), after.get(index).body(),
                        "network data must be prepared once for all displays");
                for (var packet : List.of(before.get(index), after.get(index))) {
                    var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
                    try {
                        MonitorSnapshotPayload.STREAM_CODEC.encode(buffer, packet);
                        assertEquals(packet, MonitorSnapshotPayload.STREAM_CODEC.decode(buffer));
                        assertFalse(buffer.isReadable());
                    } finally { buffer.release(); }
                }
                assertEquals(second.displayId(), after.get(index).displayId());
                assertEquals(second.rootEndpoint(), after.get(index).rootEndpoint());
                assertEquals(second.loadId(), after.get(index).loadId());
                assertEquals(21, after.get(index).observedServerTick());
                assertEquals(second.selectedTarget(), after.get(index).selectedTarget());
            }
        }
    }

    @Test void geometryOnlyMultipartCountDoesNotLeakIntoUpdatesOrNewVersions() {
        var source = multipartSnapshot(new UUID(0, 1000), 7);
        var snapshot = new MonitorSnapshotData(source.displayId(), source.rootPosition(), source.dimension(),
                source.loadId(), 3, 2, 7, true, false, null, List.of(), source.visualProfiles(),
                source.visualPhases(), source.rootEndpoint());
        var prepared = MonitorSnapshotPayload.prepare(snapshot.targets(), snapshot.visualProfiles(), snapshot.visualPhases());
        assertEquals(2, prepared.address(snapshot, true).size());
        var update = prepared.address(snapshot, false);
        assertEquals(1, update.size());
        assertFalse(update.getFirst().includesGeometry());
        assertTrue(update.getFirst().visualProfiles().isEmpty());
        assertEquals(snapshot.visualPhases(), update.getFirst().visualPhases());
        var next = MonitorSnapshotPayload.prepare(List.of(), List.of(), List.of());
        assertNotSame(update.getFirst().body(), next.address(snapshot, false).getFirst().body());
        assertTrue(next.address(snapshot, false).getFirst().visualPhases().isEmpty());
    }

    @Test void simultaneousRecipientsReceiveCompleteCachedBodies() throws Exception {
        var snapshot = multipartSnapshot(new UUID(0, 1000), 7);
        var prepared = MonitorSnapshotPayload.prepare(snapshot.targets(), snapshot.visualProfiles(), snapshot.visualPhases());
        var packets = prepared.address(snapshot, true);
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int recipient = 0; recipient < 4; recipient++) {
                futures.add(executor.submit(() -> {
                    start.await();
                    for (var packet : packets) {
                        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
                        try {
                            MonitorSnapshotPayload.STREAM_CODEC.encode(buffer, packet);
                            assertEquals(packet, MonitorSnapshotPayload.STREAM_CODEC.decode(buffer));
                            assertFalse(buffer.isReadable());
                        } finally { buffer.release(); }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var future : futures) future.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    @BeforeAll static void bootstrap() {
        net.neoforged.fml.loading.LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
    }

    @Test void ownStructureAndFrozenScanAnchorSurviveWireRoundTrip() {
        var dimension = ResourceLocation.withDefaultNamespace("overworld");
        var world = ResourceKey.create(Registries.DIMENSION, dimension);
        var endpoint = new EndpointKey.Moving(world, new UUID(0, 1), BlockPos.ZERO);
        var own = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 2));
        var foreign = new TargetRef(world, TargetRef.SourceKind.MOVING_STRUCTURE, new UUID(0, 3));
        var targets = List.of(
                new MonitorTargetData(own, true, true, 100, 64, 0, "", -1, 0, Silhouette.EMPTY, true, Optional.empty()),
                new MonitorTargetData(foreign, false, true, 110, 64, 0, "", 10, 70, Silhouette.EMPTY, false,
                        Optional.of(new ScanAnchor(endpoint, new Point(90, 0)))));
        var snapshot = new MonitorSnapshotData(new UUID(0, 4), BlockPos.ZERO, dimension, new UUID(0, 5), 1, 1, 11,
                true, false, own, targets, List.of(), List.of(), endpoint);
        var payload = MonitorSnapshotPayload.split(snapshot, true).getFirst();
        var registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), registries);
        try {
            MonitorSnapshotPayload.STREAM_CODEC.encode(buffer, payload);
            assertEquals(payload, MonitorSnapshotPayload.STREAM_CODEC.decode(buffer));
            assertFalse(buffer.isReadable(), "the new metadata consumes exactly its wire fields");
        } finally {
            buffer.release();
        }
    }
}
