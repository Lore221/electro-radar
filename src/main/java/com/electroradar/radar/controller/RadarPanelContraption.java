package com.electroradar.radar.controller;

import com.simibubi.create.api.contraption.BlockMovementChecks;
import com.simibubi.create.api.contraption.ContraptionType;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.bearing.BearingContraption;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo;
import net.minecraft.world.phys.AABB;

import net.neoforged.neoforge.event.tick.EntityTickEvent;

import com.electroradar.radar.RadarDiagnostics;
import com.electroradar.radar.network.EndpointKey;

/**
 * Create-тип только для уже проверенной стационарной апертуры. Наследование BearingContraption
 * используется ради его оси и контролируемого вращения; панельный тип в реестре необходим для
 * корректного восстановления этого класса через Contraption.fromNBT после перезапуска мира.
 */
public final class RadarPanelContraption extends BearingContraption {
    private transient boolean orphanRecoveryFailed;

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

    /**
     * Контроллер мог исчезнуть до асинхронной загрузки entity. Перед её первым обычным тиком
     * возвращаем сохранённые панели: Create при отсутствии контроллера вызвал бы discard без
     * разборки. Anchor сохраняется самой Contraption; чужой UUID никогда не усыновляется.
     */
    public static void onEntityTick(EntityTickEvent.Pre event) {
        if (!(event.getEntity() instanceof ControlledContraptionEntity entity)
                || !(entity.level() instanceof ServerLevel level) || !entity.isAlive()
                || !(entity.getContraption() instanceof RadarPanelContraption panels)) return;
        BlockPos controllerPos = panels.anchor.below();
        if (!level.hasChunkAt(controllerPos)) return;
        if (level.getBlockEntity(controllerPos) instanceof RadarControllerBlockEntity controller
                && controller.assembly().owns(entity)) return;

        // Даже при отказе разборки Create не должен продолжить тик и безвозвратно удалить entity.
        event.setCanceled(true);
        if (!panels.orphanRecoveryFailed) panels.recoverWithoutController(entity, level, controllerPos);
    }

    private void recoverWithoutController(ControlledContraptionEntity entity, ServerLevel level,
                                          BlockPos controllerPos) {
        EndpointKey.Static endpoint = new EndpointKey.Static(level.dimension(), controllerPos);
        try {
            entity.setAngle(0.0F);
            entity.disassemble();
            clearPanelProfiles(level, controllerPos);
            RadarDiagnostics.log("orphan_contraption_disassembled", null, endpoint, null,
                    level.getGameTime(), "entity=" + entity.getUUID() + " reason=controller_unavailable");
        } catch (RuntimeException exception) {
            // Create мог выставить disassembled до частичного отказа addBlocksToWorld.
            // Повтор уже не восстановил бы остаток, но удалил бы entity: сохраняем её для восстановления.
            orphanRecoveryFailed = true;
            RadarDiagnostics.logException("orphan_contraption_disassembly_failed", null, endpoint,
                    null, level.getGameTime(), "entity=" + entity.getUUID()
                            + " action=keep_entity_for_recovery", exception);
        }
    }

    @Override
    public boolean canBeStabilized(Direction facing, BlockPos localPos) {
        return false;
    }
}
