package com.electroradar.compat.sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class SableTransferMixin {
    @WrapMethod(method = "assembleBlocks", remap = false)
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel electroRadar$preflight(ServerLevel level, BlockPos anchor,
            Iterable<BlockPos> blocks, dev.ryanhcode.sable.companion.math.BoundingBox3ic bounds,
            Operation<dev.ryanhcode.sable.sublevel.ServerSubLevel> original) {
        java.util.List<BlockPos> positions = new java.util.ArrayList<>();
        blocks.forEach(p -> positions.add(p.immutable()));
        if (!SableTransferGuard.ready(level, positions)) throw new SableTransferRejected("Radar aperture must finish loading before structure assembly");
        // Direct Sable callers must include the restored aperture before allocation/other-entity migration.
        java.util.Set<BlockPos> expanded = new java.util.LinkedHashSet<>(positions);
        for (BlockPos position : positions) {
            if (level.getBlockEntity(position) instanceof com.electroradar.radar.controller.RadarControllerBlockEntity radar)
                expanded.addAll(radar.prepareForStructureTransfer());
        }
        int minX = bounds.minX(), minY = bounds.minY(), minZ = bounds.minZ();
        int maxX = bounds.maxX(), maxY = bounds.maxY(), maxZ = bounds.maxZ();
        for (BlockPos position : expanded) {
            minX = Math.min(minX, position.getX()); maxX = Math.max(maxX, position.getX());
            minY = Math.min(minY, position.getY()); maxY = Math.max(maxY, position.getY());
            minZ = Math.min(minZ, position.getZ()); maxZ = Math.max(maxZ, position.getZ());
        }
        var expandedBounds = new dev.ryanhcode.sable.companion.math.BoundingBox3i(minX, minY, minZ, maxX, maxY, maxZ);
        return original.call(level, anchor, java.util.List.copyOf(expanded), expandedBounds);
    }

    @WrapMethod(method = "moveBlocks", remap = false)
    private static void electroRadar$move(ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform,
                                          Iterable<BlockPos> blocks, Operation<Void> original) {
        SableTransferBridge.move(level, transform, blocks, positions -> original.call(level, transform, positions));
    }
}
