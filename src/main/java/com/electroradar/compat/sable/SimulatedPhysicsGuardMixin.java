package com.electroradar.compat.sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import com.simibubi.create.content.contraptions.AssemblyException;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.physics_assembler.PhysicsAssemblerBlockEntity", remap = false)
public abstract class SimulatedPhysicsGuardMixin {
    @Inject(method = "throwDisassemblyExceptions", at = @At("HEAD"), remap = false)
    private void electroRadar$check(ServerSubLevel sub, CallbackInfo ci) throws AssemblyException {
        if (!SableTransferGuard.ready(sub)) throw SableTransferGuard.assemblyFailure();
    }
}
