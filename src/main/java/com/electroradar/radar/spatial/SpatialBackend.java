package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Optional;
import com.electroradar.radar.network.EndpointKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

/** Integration interface. Implementations never force chunks or sub-levels to load. */
public interface SpatialBackend {
    Optional<Placement> placement(Level level, BlockPos position);
    default Optional<Placement> renderPlacement(Level level, BlockPos position, float partialTick) {
        return placement(level, position);
    }
    Optional<BlockPos> resolve(Level level, EndpointKey endpoint);
    List<ServerPlayer> watchers(ServerLevel level, BlockPos position);
    default java.util.List<StructureBody> structures(ServerLevel level) { return java.util.List.of(); }
    default Optional<StructureBody> structure(ServerLevel level, java.util.UUID id) {
        return structures(level).stream().filter(body -> body.target().id().equals(id)).findFirst();
    }
    /** Линейная скорость пространства прибора в мире, блоков за тик; у обычного мира — ноль. */
    default net.minecraft.world.phys.Vec3 linearVelocity(Level level, EndpointKey endpoint) {
        return net.minecraft.world.phys.Vec3.ZERO;
    }
    default void invalidate(Level level, BlockPos position) { }
    default void beginPhase(MinecraftServer server) { }
    default void completePhase() { }
    default void clear() { }
}
