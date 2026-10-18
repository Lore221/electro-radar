package com.electroradar.client.networkoutline;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;

/** Клиентская стратегия формы прибора, подключаемая без ветвления общего обработчика по всем типам. */
interface NetworkOutlineGeometryAdapter {
    /** Возвращает true, если адаптер знает синхронизированную геометрию данного BlockEntity. */
    boolean supports(BlockEntity blockEntity);

    /** Вычисляет мировой контур без обхода мира и без загрузки соседних чанков. */
    AABB bounds(ClientLevel level, BlockPos endpointPosition, BlockEntity blockEntity);
}
