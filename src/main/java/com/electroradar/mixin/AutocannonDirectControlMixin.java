package com.electroradar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import rbasamoyai.createbigcannons.cannon_control.ControlPitchContraption;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;
import rbasamoyai.createbigcannons.cannon_control.contraption.MountedAutocannonContraption;
import com.electroradar.cannon.controller.CbcDirectControl;

/** Только подключённый сетевой контроллер владеет автоматическим запуском соседней автопушки. */
@Mixin(value = MountedAutocannonContraption.class, remap = false)
public abstract class AutocannonDirectControlMixin {
    @Inject(method = "canBeFiredOnController", at = @At("HEAD"), cancellable = true, require = 1)
    private void electroradar$directFireOwner(ControlPitchContraption controller, CallbackInfoReturnable<Boolean> cir) {
        if (controller instanceof CannonMountBlockEntity mount && CbcDirectControl.owns(mount)) {
            cir.setReturnValue(false);
        }
    }
}
