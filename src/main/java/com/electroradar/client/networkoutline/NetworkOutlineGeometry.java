package com.electroradar.client.networkoutline;

import java.util.List;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Общий выбор геометрии: специальные приборы используют адаптер, остальные — форму блока. */
final class NetworkOutlineGeometry {
    private static final List<NetworkOutlineGeometryAdapter> ADAPTERS =
            List.of(new MonitorOutlineGeometryAdapter());

    private NetworkOutlineGeometry() {
    }

    /** Возвращает клиентский мировой AABB уже проверенного BlockEntity. */
    static AABB bounds(ClientLevel level, BlockPos position, BlockEntity blockEntity) {
        for (NetworkOutlineGeometryAdapter adapter : ADAPTERS) {
            if (adapter.supports(blockEntity)) {
                return adapter.bounds(level, position, blockEntity);
            }
        }

        VoxelShape shape = level.getBlockState(position).getShape(level, position);
        if (shape.isEmpty()) {
            // Невидимая collision-форма не должна скрыть зарегистрированный прибор целиком.
            return new AABB(position.getX(), position.getY(), position.getZ(),
                    position.getX() + 1, position.getY() + 1, position.getZ() + 1);
        }
        return shape.bounds().move(position);
    }
}
