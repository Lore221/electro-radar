package com.electroradar.radar.alarm;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.network.NetworkBoundBlockItem;
import com.electroradar.radar.network.NetworkId;

/** Предмет потребителя использует общие привязку, коммутатор и подсветку сети. */
public final class ShellAlarmItem extends NetworkBoundBlockItem {
    public ShellAlarmItem(Block block) { super(block, new Item.Properties()); }

    @Override protected void initializePlacedBlock(BlockEntity entity, NetworkId network) {
        if (entity instanceof ShellAlarmBlockEntity alarm && network != null) alarm.reconfigureNetwork(network);
    }
}
