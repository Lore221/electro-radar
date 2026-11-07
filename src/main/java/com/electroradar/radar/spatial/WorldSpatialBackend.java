package com.electroradar.radar.spatial;

import java.util.List;
import java.util.Optional;
import com.electroradar.radar.network.EndpointKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public class WorldSpatialBackend implements SpatialBackend {
    @Override public Optional<Placement> placement(Level level, BlockPos position) {
        return level.hasChunkAt(position) ? Optional.of(new Placement(
                new EndpointKey.Static(level.dimension(), position), position, SpatialTransform.IDENTITY))
                : Optional.empty();
    }
    @Override public Optional<BlockPos> resolve(Level level, EndpointKey endpoint) {
        if (!level.dimension().equals(endpoint.dimension()) || !(endpoint instanceof EndpointKey.Static fixed)
                || !level.hasChunkAt(fixed.position())) return Optional.empty();
        return Optional.of(fixed.position());
    }
    @Override public List<ServerPlayer> watchers(ServerLevel level, BlockPos position) {
        return level.getChunkSource().chunkMap.getPlayers(new ChunkPos(position), false);
    }
}
