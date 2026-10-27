package com.electroradar.radar.monitor;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.electroradar.radar.network.NetworkBoundBlockItem;
import com.electroradar.radar.network.NetworkId;

/**
 * Сетевой предмет монитора добавляет только создание идентичности физической панели.
 * Привязка по ПКМ, outline и порядок переноса NetworkId принадлежат общему классу.
 */
public final class RadarDisplayItem extends NetworkBoundBlockItem {
    public RadarDisplayItem(Block block, Properties properties) {
        super(block, properties, "message.electroradar.monitor.bound");
    }

    /** Вызывается общим предметом только после успешной установки на сервере. */
    @Override
    protected void initializePlacedBlock(BlockEntity placed, NetworkId networkId) {
        if (placed instanceof RadarDisplayBlockEntity display) {
            display.initializeStandalone(networkId);
        }
    }
}
