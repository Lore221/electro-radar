package com.electroradar.radar.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.network.EndpointKey;
import com.electroradar.radar.network.NetworkId;
import com.electroradar.radar.network.NetworkIdBindingTarget;
import com.electroradar.radar.network.NetworkItemBinding;
import com.electroradar.radar.network.NetworkOutlineActivatingItem;

/** Предмет любого из трёх контроллеров копирует только NetworkId и сохраняет прочие CUSTOM_DATA. */
public final class RadarControllerItem extends BlockItem implements NetworkOutlineActivatingItem {
    public RadarControllerItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        BlockPos pos = context.getClickedPos();
        Level level = context.getLevel();
        if (!(level.getBlockState(pos).getBlock() instanceof RadarControllerBlock)) return super.useOn(context);
        if (level.isClientSide) return InteractionResult.sidedSuccess(true);
        if (!(level instanceof ServerLevel) || !(level.getBlockEntity(pos) instanceof NetworkIdBindingTarget target)) {
            return InteractionResult.CONSUME;
        }
        NetworkId networkId = target.getOrCreateNetworkIdForBinding();
        NetworkItemBinding.write(context.getItemInHand(), networkId);
        Player player = context.getPlayer();
        if (player != null) {
            player.displayClientMessage(Component.translatable("message.electroradar.controller.bound"), true);
        }
        return InteractionResult.sidedSuccess(false);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        NetworkId networkId = NetworkItemBinding.read(context.getItemInHand());
        BlockPos pos = context.getClickedPos();
        InteractionResult result = super.place(context);
        Level level = context.getLevel();
        if (level.isClientSide || !result.consumesAction() || networkId == null) return result;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity instanceof NetworkIdBindingTarget target) {
            target.applyNetworkIdFromItem(networkId);
        }
        return result;
    }
}
