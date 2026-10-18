package com.electroradar.client.networkoutline;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import com.electroradar.radar.monitor.RadarDisplayBlock;
import com.electroradar.radar.monitor.RadarDisplayBlockEntity;
import com.electroradar.radar.monitor.RadarDisplayStructureResolver;

/**
 * Преобразует уже синхронизированную геометрию корня монитора в один контур сборки.
 * Активный прямоугольник выбирается серверной сборкой и приходит в данных BlockEntity;
 * клиент здесь только превращает его координаты в AABB и не ищет соседние панели.
 */
final class MonitorOutlineGeometryAdapter implements NetworkOutlineGeometryAdapter {
    @Override
    public boolean supports(BlockEntity blockEntity) {
        return blockEntity instanceof RadarDisplayBlockEntity;
    }

    @Override
    public AABB bounds(ClientLevel level, BlockPos endpointPosition, BlockEntity blockEntity) {
        RadarDisplayBlockEntity display = (RadarDisplayBlockEntity) blockEntity;
        BlockPos root = endpointPosition.immutable();
        // До прихода update tag корень может ещё не иметь DisplayId или прямоугольника.
        // В этом коротком переходном состоянии виден только сам корневой блок.
        if (!display.isRoot() || !display.rootPosition().equals(root) || display.displayId() == null) {
            return display.isRoot() && display.rootPosition().equals(root)
                    ? panelBounds(level, root, display.getBlockState()) : null;
        }

        int width = display.rectangleWidth();
        int height = display.rectangleHeight();
        if (width < 1 || height < 1 || width > RadarDisplayStructureResolver.MAX_SIZE
                || height > RadarDisplayStructureResolver.MAX_SIZE
                || !display.getBlockState().hasProperty(RadarDisplayBlock.FACING)) {
            return panelBounds(level, root, display.getBlockState());
        }

        BlockPos origin = display.rectangleOrigin();
        Direction facing = display.getBlockState().getValue(RadarDisplayBlock.FACING);
        BlockPos farPanel = RadarDisplayStructureResolver.at(origin, facing, width - 1, height - 1);
        BlockState state = display.getBlockState();
        VoxelShape shape = state.getShape(level, root);
        AABB local = shape.isEmpty()
                ? new AABB(0, 0, 0, 1, 1, 1)
                : shape.bounds();

        // Расширяем только размер сборки, сохраняя локальные отступы реальной формы панели.
        // У монитора толщина по нормали к экрану равна 12/16 блока (два боковых отступа по 2/16),
        // поэтому подстановка полного куба здесь дала бы заметно лишнюю глубину контура.
        double minX = Math.min(origin.getX() + local.minX, farPanel.getX() + local.minX);
        double maxX = Math.max(origin.getX() + local.maxX, farPanel.getX() + local.maxX);
        double minY = origin.getY() + local.minY;
        double maxY = origin.getY() + height - 1 + local.maxY;
        double minZ = Math.min(origin.getZ() + local.minZ, farPanel.getZ() + local.minZ);
        double maxZ = Math.max(origin.getZ() + local.maxZ, farPanel.getZ() + local.maxZ);
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }

    /** Возвращает форму одной панели; пустая форма имеет согласованный fallback полного блока. */
    private static AABB panelBounds(ClientLevel level, BlockPos position, BlockState state) {
        VoxelShape shape = state.getShape(level, position);
        if (shape.isEmpty()) {
            return new AABB(position.getX(), position.getY(), position.getZ(),
                    position.getX() + 1, position.getY() + 1, position.getZ() + 1);
        }
        return shape.bounds().move(position);
    }
}
