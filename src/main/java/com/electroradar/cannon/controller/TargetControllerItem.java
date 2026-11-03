package com.electroradar.cannon.controller;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.network.NetworkBoundBlockItem;
import com.electroradar.radar.network.NetworkId;

/**
 * Предмет контроллера: как и монитор, копирует сеть по ПКМ на её устройство и передаёт её
 * поставленному контроллеру. Контроллер — потребитель, поэтому сеть он не создаёт.
 */
public final class TargetControllerItem extends NetworkBoundBlockItem {
    public TargetControllerItem(Block block, Properties properties) {
        super(block, properties, "message.electroradar.target_controller.bound");
    }

    @Override
    protected void initializePlacedBlock(BlockEntity placed, NetworkId networkId) {
        if (networkId != null && placed instanceof TargetControllerBlockEntity controller) {
            controller.bindNetwork(networkId);
        }
    }

    /** Предметные свойства по умолчанию; отдельная фабрика держит регистрацию компактной. */
    static TargetControllerItem create(Block block) {
        return new TargetControllerItem(block, new Item.Properties());
    }
}
