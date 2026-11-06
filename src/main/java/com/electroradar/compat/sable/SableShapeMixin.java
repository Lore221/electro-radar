package com.electroradar.compat.sable;

import com.electroradar.radar.spatial.RadarSpatial;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot", remap = false)
public abstract class SableShapeMixin {
    @Inject(method = "onBlockChange", at = @At("TAIL"), remap = false)
    private void electroRadar$invalidate(BlockPos position, BlockState state, CallbackInfo ci) {
        var plot = (ServerLevelPlot)(Object)this;
        RadarSpatial.backend().invalidate(plot.getSubLevel().getLevel(), position);
    }
}
