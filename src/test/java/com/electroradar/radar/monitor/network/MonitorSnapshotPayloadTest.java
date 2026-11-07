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

class MonitorSnapshotPayloadTest {
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
