package com.electroradar.radar.monitor;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import com.electroradar.radar.controller.RadarControllerBinding;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkItemBinding;
import com.electroradar.radar.network.NetworkOutlineActivatingItem;

/**
 * Предмет монитора. Его ПКМ по установленному контроллеру записывает только NetworkId, а обычное размещение
 * переносит привязку на новую панель до первой серверной регистрации корневого потребителя.
 */
public final class RadarDisplayItem extends BlockItem implements NetworkOutlineActivatingItem {
    public RadarDisplayItem(Block block, Properties properties) {
        super(block, properties);
    }

    /** Серверная привязка к любому из трёх типов контроллера; клиент только завершает interaction. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult result = RadarControllerBinding.bindToController(
                context, "message.electroradar.monitor.bound");
        return result == InteractionResult.PASS ? super.useOn(context) : result;
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
