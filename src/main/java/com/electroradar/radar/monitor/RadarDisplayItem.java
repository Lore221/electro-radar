package com.electroradar.radar.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.debug.DebugRadarBlockEntity;
import com.electroradar.radar.debug.DebugRadarContent;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkItemBinding;

/**
 * Предмет монитора. Его ПКМ по тестовому радару записывает только NetworkId, а обычное размещение
 * переносит привязку на новую панель до первой серверной регистрации корневого потребителя.
 */
public final class RadarDisplayItem extends BlockItem {
    public RadarDisplayItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Серверная привязка к тестовому радару; клиент только подтверждает взаимодействие. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos position = context.getClickedPos();
        if (!level.getBlockState(position).is(DebugRadarContent.DEBUG_RADAR_BLOCK.get())) {
            return super.useOn(context);
        }
        if (level.isClientSide) {
            return InteractionResult.sidedSuccess(true);
        }
        if (!(level instanceof ServerLevel)
                || !(level.getBlockEntity(position) instanceof DebugRadarBlockEntity radar)) {
            return InteractionResult.CONSUME;
        }

        NetworkId networkId = radar.getOrCreateNetworkIdForBinding();
        NetworkItemBinding.write(context.getItemInHand(), networkId);
        Player player = context.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.electroradar.monitor.bound"), true);
        }
        return InteractionResult.sidedSuccess(false);
    }

    /**
     * Сохраняет привязку до vanilla-размещения: после успешного super.place новый BlockEntity
     * получает NetworkId до первого server tick, где открывается ConsumerConnection.
     */
    @Override
    public InteractionResult place(BlockPlaceContext context) {
        NetworkId boundNetworkId = NetworkItemBinding.read(context.getItemInHand());
        BlockPos position = context.getClickedPos();
        InteractionResult result = super.place(context);
        Level level = context.getLevel();
        if (level.isClientSide || !result.consumesAction()) {
            return result;
        }
        if (level.getBlockEntity(position) instanceof RadarDisplayBlockEntity display) {
            display.initializeStandalone(boundNetworkId);
        }
        return result;
    }
}
