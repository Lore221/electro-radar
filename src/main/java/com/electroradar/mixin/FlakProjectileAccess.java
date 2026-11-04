package com.electroradar.mixin;

import net.minecraft.core.Position;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import rbasamoyai.createbigcannons.munitions.autocannon.flak.FlakAutocannonProjectile;

/** Штатный подрыв зенитного снаряда: фугасный взрыв и осколочный конус CBC в заданной точке. */
@Mixin(value = FlakAutocannonProjectile.class, remap = false)
public interface FlakProjectileAccess {
    @Invoker("detonate")
    void electroradar$detonate(Position position);
}
