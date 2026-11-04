package com.electroradar.cannon.controller;

import net.minecraft.world.item.Item;
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
    protected void initializePlacedBlock(BlockEntity placed, NetworkId networkId) {
        if (networkId != null && placed instanceof InterceptControllerBlockEntity controller) {
            controller.bindNetwork(networkId);
        }
    }

    static InterceptControllerItem create(Block block) {
        return new InterceptControllerItem(block, new Item.Properties());
    }
}
