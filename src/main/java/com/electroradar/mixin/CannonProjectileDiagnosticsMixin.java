package com.electroradar.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.ProjectileContext;
import com.electroradar.cannon.diagnostics.CannonDiagnostics;

/** Перехват именно вызовов CBC: подклассы могут переопределять обработку контакта. */
@Mixin(value = AbstractCannonProjectile.class, remap = false)
public abstract class CannonProjectileDiagnosticsMixin {
    @Shadow protected abstract Vec3 getForces(Vec3 position, Vec3 velocity);

    @Inject(method = "clipAndDamage", at = @At("HEAD"), require = 1)
    private void electroradar$sample(CallbackInfo ci) {
        AbstractCannonProjectile projectile = (AbstractCannonProjectile) (Object) this;
        if (projectile.level().isClientSide) return;
        Vec3 start = projectile.position();
        Vec3 end = start.add(projectile.getDeltaMovement()).add(getForces(start, projectile.getDeltaMovement()));
        CannonDiagnostics.sample(projectile, start, end);
    }

    @WrapOperation(method = "clipAndDamage", at = @At(value = "INVOKE", target =
            "Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile;calculateBlockPenetration(Lrbasamoyai/createbigcannons/munitions/ProjectileContext;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/phys/BlockHitResult;)Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile$ImpactResult;"), require = 1)
    private AbstractCannonProjectile.ImpactResult electroradar$blockContact(AbstractCannonProjectile projectile,
            ProjectileContext context, BlockState state, BlockHitResult hit,
            Operation<AbstractCannonProjectile.ImpactResult> original) {
        var result = original.call(projectile, context, state, hit);
        if (!projectile.level().isClientSide) {
            CannonDiagnostics.contact(projectile, hit.getLocation(), "block", hit.getBlockPos(), result);
        }
        return result;
    }

    @WrapOperation(method = "clipAndDamage", at = @At(value = "INVOKE", target =
            "Lrbasamoyai/createbigcannons/munitions/AbstractCannonProjectile;onHitEntity(Lnet/minecraft/world/entity/Entity;Lrbasamoyai/createbigcannons/munitions/ProjectileContext;)Z"), require = 1)
    private boolean electroradar$entityContact(AbstractCannonProjectile projectile, Entity target,
            ProjectileContext context, Operation<Boolean> original) {
        Vec3 point = null;
        if (!projectile.level().isClientSide) {
            Vec3 start = projectile.position();
            Vec3 end = start.add(projectile.getDeltaMovement()).add(getForces(start, projectile.getDeltaMovement()));
            double reach = Math.max(projectile.getBbWidth(), projectile.getBbHeight()) / 2.0D;
            point = target.getBoundingBox().inflate(reach).clip(start, end).orElse(start);
        }
        boolean remove = original.call(projectile, target, context);
        if (point != null) CannonDiagnostics.contact(projectile, point, "entity", target.getUUID(), "remove=" + remove);
        return remove;
    }
}
