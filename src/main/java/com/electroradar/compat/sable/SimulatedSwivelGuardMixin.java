package com.electroradar.compat.sable;

import java.util.UUID;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity", remap = false)
public abstract class SimulatedSwivelGuardMixin {
    @Shadow private UUID subLevelID;
    @Shadow protected AssemblyException lastException;
    @Inject(method = "disassemble", at = @At("HEAD"), cancellable = true, remap = false)
    private void electroRadar$check(CallbackInfo ci) {
        var self = (SmartBlockEntity)(Object)this;
        if (self.getLevel() == null || self.getLevel().isClientSide || subLevelID == null) return;
        var container = SubLevelContainer.getContainer(self.getLevel());
        var sub = container == null ? null : container.getSubLevel(subLevelID);
        if (!SableTransferGuard.ready(sub)) {
            lastException = SableTransferGuard.assemblyFailure(); self.sendData(); ci.cancel();
        }
    }
}
