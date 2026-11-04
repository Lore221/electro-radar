package com.electroradar.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;

/** Флаг CBC «удалить в следующем тике»: так снаряд уходит после подрыва штатным путём. */
@Mixin(value = AbstractCannonProjectile.class, remap = false)
public interface CannonProjectileAccess {
    @Accessor("removeNextTick")
    boolean electroradar$removeNextTick();

    @Accessor("removeNextTick")
    void electroradar$setRemoveNextTick(boolean value);
}
