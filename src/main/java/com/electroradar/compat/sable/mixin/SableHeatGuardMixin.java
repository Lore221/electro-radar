package com.electroradar.compat.sable.mixin;

import com.electroradar.compat.sable.SableTransferGuard;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager", remap = false)
public abstract class SableHeatGuardMixin {
    @Shadow @Final private ServerSubLevel subLevel;
    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroRadar$defer(CallbackInfo ci) {
        if (!SableTransferGuard.ready(subLevel)) ci.cancel();
    }
}
