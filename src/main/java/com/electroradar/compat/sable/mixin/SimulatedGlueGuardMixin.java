package com.electroradar.compat.sable.mixin;

import com.electroradar.compat.sable.SableTransferGuard;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.merging_glue.MergingGlueBlockEntity", remap = false)
public abstract class SimulatedGlueGuardMixin {
    @Shadow private boolean isController;
    @Shadow private int ageTicks;
    @Shadow private BlockPos partnerPosition;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroRadar$defer(CallbackInfo ci) {
        var self = (BlockEntity)(Object)this;
        var level = self.getLevel();
        if (level == null || level.isClientSide || !isController || ageTicks <= 12) return;
        if (!SableTransferGuard.readyAt(level, self.getBlockPos())
                || partnerPosition != null && !SableTransferGuard.readyAt(level, partnerPosition)) ci.cancel();
    }
}
