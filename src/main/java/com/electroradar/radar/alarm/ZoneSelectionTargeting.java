package com.electroradar.radar.alarm;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The client preview and server selection use the same targeting rules. */
public final class ZoneSelectionTargeting {
    private ZoneSelectionTargeting() { }

    public static BlockHitResult trace(Player player) {
        HitResult hit = player.pick(75, 1, false);
        return hit instanceof BlockHitResult block && hit.getType() == HitResult.Type.BLOCK ? block : null;
    }

    public static BlockPos block(Player player, InteractionHand hand, BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        boolean replaceable = player.level().getBlockState(pos)
                .canBeReplaced(new BlockPlaceContext(new UseOnContext(player, hand, hit)));
        return hit.getDirection().getAxis().isVertical() && !replaceable ? pos.relative(hit.getDirection()) : pos;
    }

    public static BlockPos target(Player player, InteractionHand hand, boolean air, int range) {
        if (air) return BlockPos.containing(player.getEyePosition().add(player.getLookAngle().scale(range)));
        BlockHitResult hit = trace(player);
        return hit == null ? null : block(player, hand, hit);
    }
}
