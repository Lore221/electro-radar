package com.electroradar.radar.network;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/** Единая привязка сетевых предметов к источнику без зависимости от класса его блока. */
final class NetworkItemBindingInteraction {
    private NetworkItemBindingInteraction() {
    }

    /** PASS оставляет взаимодействие блока и обычное размещение предмета. */
    static InteractionResult copyFromSource(UseOnContext context, String messageKey) {
        Level level = context.getLevel();
        if (!level.hasChunkAt(context.getClickedPos())
                || !(level.getBlockEntity(context.getClickedPos()) instanceof NetworkIdBindingTarget target)) {
            return InteractionResult.PASS;
        }
        Player player = context.getPlayer();
        if (player == null || !player.mayBuild() || !level.mayInteract(player, context.getClickedPos())) {
            return InteractionResult.FAIL;
        }
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel)) return InteractionResult.CONSUME;
        NetworkItemBinding.write(context.getItemInHand(), target.getOrCreateNetworkIdForBinding());
        player.displayClientMessage(Component.translatable(messageKey), true);
        return InteractionResult.sidedSuccess(false);
    }
}
