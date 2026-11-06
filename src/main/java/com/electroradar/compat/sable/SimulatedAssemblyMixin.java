package com.electroradar.compat.sable;

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
    @Inject(method = "disassembleAndAddCreateContraptions", at = @At("HEAD"), remap = false)
    private static void electroRadar$normalize(Level level, BoundingBox3ic bounds, Collection<BlockPos> blocks,
                                               boolean passGlues, List<AABB> glues, CallbackInfo ci) {
        for (BlockPos position : List.copyOf(blocks)) {
            if (level.getBlockEntity(position) instanceof RadarControllerBlockEntity radar) radar.normalizeForStructureTransfer();
        }
    }
}
