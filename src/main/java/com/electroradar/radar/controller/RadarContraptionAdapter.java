package com.electroradar.radar.controller;

import java.util.Map;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.StructureTransform;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import com.electroradar.radar.RadarDiagnostics;

/**
 * Единственный слой, меняющий блоки мира ради Create. До удаления Create уже собрал и проверил
 * все панели; отказ при спавне entity восстанавливается штатным addBlocksToWorld без дропов.
 */
public final class RadarContraptionAdapter {
    private RadarContraptionAdapter() {
    }

    public static Result assemble(ServerLevel level, RadarControllerBlockEntity controller,
                                  RadarMode mode, RadarAssemblyFinder.Discovery discovery) {
        RadarPanelContraption contraption = new RadarPanelContraption(discovery.facing());
        ControlledContraptionEntity entity;
        try {
            if (!contraption.assembleValidated(level, controller.getBlockPos(), discovery, mode)) {
                return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED, null);
            }
            entity = ControlledContraptionEntity.create(level, controller, contraption);
            BlockPos anchor = controller.getBlockPos().above();
            entity.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
            entity.setRotationAxis(discovery.facing().getClockWise().getAxis());
            entity.setAngle(0.0F);
        } catch (Exception exception) {
            RadarDiagnostics.logException("contraption_preparation_failed", controller.networkId(),
                    controller.endpoint(), null, controller.currentTickForDiagnostics(),
                    "panels=" + discovery.panelCount(), exception);
            return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED, exception);
        }

        boolean blocksRemoved = false;
        try {
            contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
            blocksRemoved = true;
            if (!level.addFreshEntity(entity)) {
                restoreWithCreate(level, contraption, discovery, controller.getBlockPos());
                return new Result(null, RadarAssemblyFinder.Failure.ENTITY_REJECTED, null);
            }
            return new Result(entity, RadarAssemblyFinder.Failure.NONE, null);
        } catch (Exception exception) {
            if (blocksRemoved) {
                try {
                    restoreWithCreate(level, contraption, discovery, controller.getBlockPos());
                } catch (Exception restoreException) {
                    exception.addSuppressed(restoreException);
                    restoreMissingPanels(level, discovery.sourceStates());
                }
            } else {
                // Если Create оборвался посреди removeBlocksFromWorld, вернуть только исчезнувшие
                // исходные панели; существующие блоки никогда не затираются и предметы не дропаются.
                restoreMissingPanels(level, discovery.sourceStates());
            }
            RadarDiagnostics.logException("contraption_capture_failed", controller.networkId(),
                    controller.endpoint(), null, controller.currentTickForDiagnostics(),
                    "panels=" + discovery.panelCount() + " rolledBack=true", exception);
            return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED, exception);
        }
    }

    private static void restoreWithCreate(ServerLevel level, RadarPanelContraption contraption,
                                          RadarAssemblyFinder.Discovery discovery, BlockPos controllerPos) {
        // StructureBlockInfo уже сохранён в локальных координатах contraption. При откате
        // Create должен применить тот же мировой anchor, который использовался при захвате.
        contraption.addBlocksToWorld(level, new StructureTransform(controllerPos.above(), 0, 0, 0));
        contraption.clearPanelProfiles(level, controllerPos);
        // Create выбрасывает entity при disassemble; если он уже был принят, безопасно убрать
        // оставшийся объект после восстановления по штатному transform.
        if (contraption.entity != null && contraption.entity.isAlive()) contraption.entity.discard();
        restoreMissingPanels(level, discovery.sourceStates());
    }

    private static void restoreMissingPanels(ServerLevel level, Map<BlockPos, BlockState> sourceStates) {
        for (Map.Entry<BlockPos, BlockState> entry : sourceStates.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            BlockState current = level.getBlockState(pos);
            if (current.is(Blocks.AIR)) {
                level.setBlock(pos, entry.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS
                        | Block.UPDATE_SUPPRESS_DROPS);
            }
        }
    }

    public record Result(ControlledContraptionEntity entity,
                         RadarAssemblyFinder.Failure failure, Exception cause) {
        public boolean success() {
            return entity != null && failure == RadarAssemblyFinder.Failure.NONE;
        }
    }
}
