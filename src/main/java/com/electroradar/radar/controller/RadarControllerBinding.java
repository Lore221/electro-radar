package com.electroradar.radar.controller;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import com.electroradar.radar.network.NetworkIdBindingTarget;
import com.electroradar.radar.network.NetworkItemBinding;

/** Общая серверная привязка предметов; PASS оставляет обычное размещение их BlockItem. */
public final class RadarControllerBinding {
    private RadarControllerBinding() {
    }

    public static InteractionResult bindToController(UseOnContext context, String messageKey) {
        Level level = context.getLevel();
        if (!(level.getBlockState(context.getClickedPos()).getBlock() instanceof RadarControllerBlock)) {
            return InteractionResult.PASS;
        }
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel)
                || !(level.getBlockEntity(context.getClickedPos()) instanceof NetworkIdBindingTarget target)) {
            return InteractionResult.CONSUME;
        }
        NetworkItemBinding.write(context.getItemInHand(), target.getOrCreateNetworkIdForBinding());
        Player player = context.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable(messageKey), true);
        }
        return InteractionResult.sidedSuccess(false);
    }
}
