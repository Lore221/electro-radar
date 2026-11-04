package com.electroradar.cannon.controller;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.HitResult;
import rbasamoyai.createbigcannons.munitions.AbstractCannonProjectile;
import rbasamoyai.createbigcannons.munitions.fuzes.FuzeItem;

/**
 * Взрыватель перехвата: ставится в зенитный патрон штатным рецептом CBC, как любой {@link FuzeItem}.
 *
 * <p>Контроллер перехвата программирует его при выстреле ({@link ProgrammedFuzes}). С программой
 * взрыватель срабатывает рядом с выбранным снарядом большой пушки с упреждением и подрывается сам
 * после окна встречи. Без программы (выстрел вручную или другим контроллером) он работает как
 * ударный: подрыв при попадании, кроме рикошета, и в конце времени жизни, чтобы снаряд не падал
 * на своих.</p>
 */
public final class InterceptFuzeItem extends FuzeItem {
    public InterceptFuzeItem(Properties properties) {
        super(properties);
    }

    @Override
    public boolean onProjectileTick(ItemStack stack, AbstractCannonProjectile projectile) {
        return ProgrammedFuzes.tick(projectile);
    }

    @Override
    public boolean onProjectileImpact(ItemStack stack, AbstractCannonProjectile projectile, HitResult hitResult,
                                      AbstractCannonProjectile.ImpactResult impactResult, boolean baseFuze) {
        return !baseFuze && (impactResult.shouldRemove()
                || impactResult.kinematics() != AbstractCannonProjectile.ImpactResult.KinematicOutcome.BOUNCE);
    }

    @Override
    public boolean onProjectileExpiry(ItemStack stack, AbstractCannonProjectile projectile) {
        return true;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, context, tooltip, flag);
        tooltip.add(Component.translatable("item.electroradar.intercept_fuze.tooltip").withStyle(ChatFormatting.GRAY));
    }
}
