package com.electroradar.compat.sable;

import com.electroradar.radar.spatial.StructureBody;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.spatial.*;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Sable 2.0.6 API adapter; this class is resolved only when Sable is installed. */
public final class SableSpatialBackend extends WorldSpatialBackend {
    private boolean phaseOpen;
    private final Map<UUID, Long> retryAfter = new HashMap<>();
    private final java.util.Set<UUID> failed = new java.util.HashSet<>();
    private final SableStructureReader structureReader = new SableStructureReader();
    private final Map<ServerLevel, Map<UUID, StructureBody>> bodies = new IdentityHashMap<>();
    private final Map<Level, Map<UUID, SpatialTransform>> phaseFrames = new IdentityHashMap<>();

    @Override public void beginPhase(MinecraftServer server) {
        phaseOpen = true;
        phaseFrames.clear(); bodies.clear(); structureReader.beginPhase();
        for (ServerLevel level : server.getAllLevels()) {
            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) continue;
            Map<UUID, SpatialTransform> frames = new HashMap<>();
            for (SubLevel sub : container.getAllSubLevels()) {
                if (!sub.isRemoved()) frames.put(sub.getUniqueId(), frame(sub, sub.logicalPose()));
            }
            phaseFrames.put(level, frames);
        }
    }

    @Override public void completePhase() { phaseOpen = false; }

    @Override public void clear() { phaseFrames.clear(); bodies.clear(); structureReader.clear(); retryAfter.clear(); failed.clear(); }

    @Override public void invalidate(Level level, BlockPos position) {
        var container = SubLevelContainer.getContainer(level);
        var plot = container == null ? null : container.getPlot(new ChunkPos(position));
        if (plot != null) structureReader.invalidate(plot.getSubLevel());
    }

    @Override public java.util.List<StructureBody> structures(ServerLevel level) {
        var container = SubLevelContainer.getContainer(level);
        if (container == null) return java.util.List.of();
        java.util.List<StructureBody> result = new java.util.ArrayList<>();
        for (var sub : container.getAllSubLevels()) structure(level, sub.getUniqueId()).ifPresent(result::add);
        return java.util.List.copyOf(result);
    }

    @Override public Optional<StructureBody> structure(ServerLevel level, UUID id) {
        var cache = bodies.computeIfAbsent(level, ignored -> new HashMap<>());
        if (cache.containsKey(id)) return Optional.ofNullable(cache.get(id));
        if (!phaseOpen) return Optional.empty();
        var container = SubLevelContainer.getContainer(level);
        var sub = container == null ? null : container.getSubLevel(id);
        if (!(sub instanceof dev.ryanhcode.sable.sublevel.ServerSubLevel serverSub) || sub.isRemoved()) return Optional.empty();
        var frames = phaseFrames.get(level);
        var frame = frames == null ? null : frames.get(id);
        // A newly assembled structure joins at the next complete phase.
        if (frame == null) return Optional.empty();
        if (retryAfter.getOrDefault(id, Long.MIN_VALUE) > level.getGameTime()) return Optional.empty();
        try {
            StructureBody body = structureReader.read(serverSub, frame);
            cache.put(id, body); retryAfter.remove(id); failed.remove(id);
            return Optional.of(body);
        } catch (RuntimeException failure) {
            cache.put(id, null); retryAfter.put(id, level.getGameTime() + 20);
            if (failed.add(id)) com.electroradar.radar.RadarDiagnostics.logException("structure_geometry_unavailable",
                    null, null, null, level.getGameTime(), "structure=" + id + " retryTicks=20", failure);
            return Optional.empty();
        }
    }

    @Override public Optional<Placement> placement(Level level, BlockPos position) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(position)) return super.placement(level, position);
        var plot = container.getPlot(new ChunkPos(position));
        if (plot == null || !level.hasChunkAt(position)) return Optional.empty();
        SubLevel sub = plot.getSubLevel();
        if (sub.isRemoved()) return Optional.empty();
        EndpointKey key = new EndpointKey.Moving(level.dimension(), sub.getUniqueId(),
                position.subtract(plot.getCenterBlock()));
        Map<UUID, SpatialTransform> frames = phaseFrames.get(level);
        SpatialTransform transform = frames == null ? null : frames.get(sub.getUniqueId());
        if (transform == null) transform = frame(sub, sub.logicalPose());
        return Optional.of(new Placement(key, position, transform));
    }

    @Override public Optional<Placement> renderPlacement(Level level, BlockPos position, float partialTick) {
        Optional<Placement> logical = placement(level, position);
        if (logical.isEmpty() || !(logical.get().endpoint() instanceof EndpointKey.Moving moving)) return logical;
        var container = SubLevelContainer.getContainer(level);
        var sub = container == null ? null : container.getSubLevel(moving.structureId());
        if (sub instanceof dev.ryanhcode.sable.companion.ClientSubLevelAccess client) {
            return Optional.of(new Placement(logical.get().endpoint(), position, frame(sub, client.renderPose(partialTick))));
        }
        return logical;
    }

    @Override public Optional<BlockPos> resolve(Level level, EndpointKey endpoint) {
        if (!(endpoint instanceof EndpointKey.Moving moving)) return super.resolve(level, endpoint);
        if (!level.dimension().equals(endpoint.dimension())) return Optional.empty();
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        SubLevel sub = container == null ? null : container.getSubLevel(moving.structureId());
        if (sub == null || sub.isRemoved()) return Optional.empty();
        if (level.isClientSide && sub instanceof dev.ryanhcode.sable.sublevel.ClientSubLevel client && !client.isFinalized()) return Optional.empty();
        BlockPos pos = sub.getPlot().getCenterBlock().offset(moving.localPosition());
        if (!sub.getPlot().contains(new ChunkPos(pos)) || !level.hasChunkAt(pos)) return Optional.empty();
        return Optional.of(pos);
    }

    @Override public List<ServerPlayer> watchers(ServerLevel level, BlockPos position) {
        SubLevelContainer container = SubLevelContainer.getContainer(level);
        return container != null && container.inBounds(position)
                ? container.getPlayersTracking(new ChunkPos(position)) : super.watchers(level, position);
    }

    private static SpatialTransform frame(SubLevel sub, Pose3dc pose) {
        return frame(Vec3.atLowerCornerOf(sub.getPlot().getCenterBlock()), pose);
    }
    static SpatialTransform frame(Vec3 origin, Pose3dc pose) {
        Vec3 center = pose.transformPosition(origin);
        return new SpatialTransform(origin, center,
                pose.transformPosition(origin.add(1, 0, 0)).subtract(center),
                pose.transformPosition(origin.add(0, 1, 0)).subtract(center),
                pose.transformPosition(origin.add(0, 0, 1)).subtract(center));
    }
}
