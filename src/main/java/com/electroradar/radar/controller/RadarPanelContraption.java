package com.electroradar.radar.controller;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;

/**
 * Create-тип только для уже проверенной стационарной апертуры. Наследование BearingContraption
 * используется ради его оси и контролируемого вращения; панельный тип в реестре необходим для
 * корректного восстановления этого класса через Contraption.fromNBT после перезапуска мира.
 */
public final class RadarPanelContraption extends BearingContraption {
    public RadarPanelContraption() {
        super();
    }

    public RadarPanelContraption(Direction panelFacing) {
        // Ось совпадает с нормалью BearingContraption; знак угла выбирается отдельно от режима
        // по FACING, потому что положительное вращение поднимает передний край не во всех сторонах.
        super(false, panelFacing.getClockWise());
    }

    @Override
    public ContraptionType getType() {
        return RadarControllerContent.PANEL_CONTRAPTION_TYPE.get();
    }

    /** Автосборка движущей конструкцией запрещена: единственный вход — ручная серверная проверка. */
    @Override
    public boolean assemble(Level world, BlockPos pos) {
        return false;
    }

    boolean assembleValidated(Level level, BlockPos controllerPos,
                                     RadarAssemblyFinder.Discovery discovery, RadarMode mode)
            throws AssemblyException {
        if (!discovery.valid() || discovery.positions().isEmpty()) return false;
        anchor = controllerPos.above();
        bounds = new AABB(BlockPos.ZERO);

        // Второй проход закрывает промежуток между поиском и Create capture. До этого места
        // все проверки повторны и только читают мир, поэтому первый удаляемый блок известен заранее.
        for (BlockPos position : discovery.positions()) {
            if (!level.isLoaded(position)) return false;
            BlockState current = level.getBlockState(position);
            if (!RadarPanelBlock.isPanel(current)
                    || current.getValue(RadarPanelBlock.FACING) != discovery.facing()
                    || !current.equals(discovery.sourceStates().get(position))
                    || !BlockMovementChecks.isMovementNecessary(current, level, position)
                    || !BlockMovementChecks.isMovementAllowed(current, level, position)) {
                return false;
            }
        }

        RadarPanelBlock.PanelProfile profile = RadarPanelBlock.PanelProfile.forMode(mode);
        for (BlockPos position : discovery.positions()) {
            var captured = capture(level, position);
            StructureBlockInfo block = captured.getKey();
            BlockState profiled = block.state().setValue(RadarPanelBlock.PROFILE, profile);
            var profiledCapture = new org.apache.commons.lang3.tuple.ImmutablePair<>(
                    new StructureBlockInfo(block.pos(), profiled, block.nbt()), captured.getValue());
            addBlock(level, position, profiledCapture);
        }

        if (blocks.isEmpty()) return false;
        startMoving(level);
        // BearingContraption хранит нормаль плоскости; развёрнутый box нужен Create для поворота.
        expandBoundsAroundAxis(getFacing().getAxis());
        return true;
    }

    /** Сбрасывает только визуальный профиль в уже восстановленных Create блоках. */
    public void clearPanelProfiles(Level level, BlockPos controllerPos) {
        BlockPos anchorPos = controllerPos.above();
        for (BlockPos localPos : blocks.keySet()) {
            BlockPos worldPos = anchorPos.offset(localPos);
            if (!level.hasChunkAt(worldPos)) continue;
            BlockState state = level.getBlockState(worldPos);
            if (RadarPanelBlock.isPanel(state)
                    && state.getValue(RadarPanelBlock.PROFILE) != RadarPanelBlock.PanelProfile.SURFACE) {
                level.setBlock(worldPos, state.setValue(RadarPanelBlock.PROFILE,
                        RadarPanelBlock.PanelProfile.SURFACE),
                        net.minecraft.world.level.block.Block.UPDATE_CLIENTS
                                | net.minecraft.world.level.block.Block.UPDATE_NEIGHBORS);
            }
        }
    }

    @Override
    public boolean canBeStabilized(Direction facing, BlockPos localPos) {
        return false;
    }
}
