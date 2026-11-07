package com.electroradar.cannon.controller;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.InteractionResult;
import com.electroradar.radar.alarm.ShellAlarmBlockEntity;
import com.electroradar.radar.network.NetworkItemBinding;
import com.electroradar.radar.network.NetworkKind;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.network.NetworkBoundBlockItem;
import com.electroradar.radar.network.NetworkId;

/** Предмет контроллера перехвата: копирует сеть по ПКМ на её устройство и передаёт её поставленному блоку. */
public final class InterceptControllerItem extends NetworkBoundBlockItem {
    public InterceptControllerItem(Block block, Properties properties) {
        super(block, properties, "message.electroradar.intercept_controller.bound");
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) { return copyInterception(context); }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        InteractionResult copied = copyInterception(context);
        return copied == InteractionResult.PASS ? place(new BlockPlaceContext(context)) : copied;
    }

    private InteractionResult copyInterception(UseOnContext context) {
        var level = context.getLevel();
        if (!level.hasChunkAt(context.getClickedPos())
                || !(level.getBlockEntity(context.getClickedPos()) instanceof ShellAlarmBlockEntity alarm)) return InteractionResult.PASS;
        var player = context.getPlayer();
        if (player == null || !player.mayBuild() || !level.mayInteract(player, context.getClickedPos())) return InteractionResult.FAIL;
        if (!level.isClientSide) {
            NetworkItemBinding.write(context.getItemInHand(), alarm.getOrCreateInterceptionNetwork(), NetworkKind.INTERCEPTION);
            player.displayClientMessage(Component.translatable("message.electroradar.intercept_controller.bound"), true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public InteractionResult place(BlockPlaceContext context) {
        if (NetworkItemBinding.read(context.getItemInHand()) != null
                && NetworkItemBinding.kind(context.getItemInHand()) != NetworkKind.INTERCEPTION) return InteractionResult.FAIL;
        return super.place(context);
    }

    @Override
    protected void initializePlacedBlock(BlockEntity placed, NetworkId networkId) {
        if (networkId != null && placed instanceof InterceptControllerBlockEntity controller) {
            controller.bindNetwork(networkId);
        }
    }

    static InterceptControllerItem create(Block block) {
        return new InterceptControllerItem(block, new Item.Properties());
    }
}
