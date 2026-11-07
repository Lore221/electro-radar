package com.electroradar.compat.sable.mixin;

import com.electroradar.compat.sable.SableTransferGuard;
import com.electroradar.compat.sable.SableTransferRejected;

import java.util.Collection;
import java.util.List;
import com.electroradar.radar.controller.RadarControllerBlockEntity;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.SimAssemblyHelper", remap = false)
public abstract class SimulatedAssemblyMixin {
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "assembleFromSingleBlock",
            at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/util/SimAssemblyHelper;disassembleAndAddCreateContraptions(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;Ljava/util/Collection;ZLjava/util/List;)V"), remap = false)
    private static void electroRadar$assemblyFailure(Level level, BoundingBox3ic bounds, Collection<BlockPos> blocks,
            boolean passGlues, List<AABB> glues, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original)
            throws com.simibubi.create.content.contraptions.AssemblyException {
        try { original.call(level, bounds, blocks, passGlues, glues); }
        catch (SableTransferRejected rejected) { throw SableTransferGuard.assemblyFailure(); }
    }

    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(method = "assembleFromSingleBlock",
            at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/api/SubLevelAssemblyHelper;assembleBlocks(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;Ljava/lang/Iterable;Ldev/ryanhcode/sable/companion/math/BoundingBox3ic;)Ldev/ryanhcode/sable/sublevel/ServerSubLevel;"), remap = false)
    private static dev.ryanhcode.sable.sublevel.ServerSubLevel electroRadar$finishProof(
            net.minecraft.server.level.ServerLevel level, BlockPos anchor, Iterable<BlockPos> blocks, BoundingBox3ic bounds,
            com.llamalad7.mixinextras.injector.wrapoperation.Operation<dev.ryanhcode.sable.sublevel.ServerSubLevel> original)
            throws com.simibubi.create.content.contraptions.AssemblyException {
        java.util.List<RadarControllerBlockEntity> controllers = new java.util.ArrayList<>();
        for (BlockPos position : blocks) if (level.getBlockEntity(position) instanceof RadarControllerBlockEntity radar) controllers.add(radar);
        try { return original.call(level, anchor, blocks, bounds); }
        catch (SableTransferRejected rejected) { throw SableTransferGuard.assemblyFailure(); }
        finally { for (var radar : controllers) radar.releaseStructureTransferProof(); }
    }

    @com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod(method = "disassembleAndAddCreateContraptions", remap = false)
    private static void electroRadar$normalize(Level level, BoundingBox3ic bounds, Collection<BlockPos> blocks,
            boolean passGlues, List<AABB> glues, com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original) {
        java.util.List<RadarControllerBlockEntity> controllers = new java.util.ArrayList<>();
        for (BlockPos position : List.copyOf(blocks)) {
            if (level.getBlockEntity(position) instanceof RadarControllerBlockEntity radar) {
                if (!radar.canTransferStructure()) throw new SableTransferRejected("Radar aperture must finish loading before assembly: " + position);
                controllers.add(radar);
            }
        }
        for (var radar : controllers) radar.normalizeForStructureTransfer();
        boolean completed = false;
        try {
            original.call(level, bounds, blocks, passGlues, glues);
            for (var radar : controllers) if (!radar.confirmExternalStructureDisassembly())
                throw new SableTransferRejected("Radar aperture was not completely restored by Simulated: " + radar.getBlockPos());
            completed = true;
        } finally {
            if (!completed) for (var radar : controllers) radar.releaseStructureTransferProof();
        }
    }
}
