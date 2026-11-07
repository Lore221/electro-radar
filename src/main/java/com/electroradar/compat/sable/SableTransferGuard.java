package com.electroradar.compat.sable;

import com.electroradar.radar.controller.RadarControllerBlockEntity;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

public final class SableTransferGuard {
    private SableTransferGuard() { }
    public static boolean ready(Level level, Iterable<BlockPos> positions) {
        for (BlockPos position : positions) {
            if (!level.hasChunkAt(position)) return false;
            if (level.getBlockEntity(position) instanceof RadarControllerBlockEntity radar && !radar.canTransferStructure()) return false;
        }
        return true;
    }
    public static boolean ready(SubLevel sub) {
        if (sub == null) return true;
        for (var holder : sub.getPlot().getLoadedChunks()) {
            for (var be : holder.getChunk().getBlockEntities().values()) {
                if (be instanceof RadarControllerBlockEntity radar && !radar.canTransferStructure()) return false;
            }
        }
        return true;
    }
    public static boolean readyAt(Level level, BlockPos position) {
        var container = SubLevelContainer.getContainer(level);
        var plot = container == null ? null : container.getPlot(new ChunkPos(position));
        return plot == null || ready(plot.getSubLevel());
    }
    public static com.simibubi.create.content.contraptions.AssemblyException assemblyFailure() {
        return new com.simibubi.create.content.contraptions.AssemblyException(
                net.minecraft.network.chat.Component.translatable("message.electroradar.assembly.transfer_unavailable"));
    }
}
