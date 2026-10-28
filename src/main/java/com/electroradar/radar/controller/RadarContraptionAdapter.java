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
final class RadarContraptionAdapter {
    private RadarContraptionAdapter() {
    }

    public static Result assemble(ServerLevel level, RadarControllerBlockEntity controller,
                                  RadarMode mode, RadarAssemblyFinder.Discovery discovery) {
        RadarPanelContraption contraption = new RadarPanelContraption(
                discovery.apertureKind(), discovery.facing(), discovery.apertureCount());
        ControlledContraptionEntity entity;
        try {
            if (!contraption.assembleValidated(level, controller.getBlockPos(), discovery, mode)) {
                return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED);
            }
            entity = ControlledContraptionEntity.create(level, controller, contraption);
            BlockPos anchor = controller.getBlockPos().above();
            entity.setPos(anchor.getX(), anchor.getY(), anchor.getZ());
            entity.setRotationAxis(discovery.facing().getClockWise().getAxis());
            entity.setAngle(0.0F);
        } catch (Exception exception) {
            RadarDiagnostics.logException("contraption_preparation_failed", controller.networkId(),
                    controller.endpoint(), null, controller.currentTickForDiagnostics(),
                    "aperture=" + discovery.apertureKind().serializedName()
                            + " count=" + discovery.apertureCount(), exception);
            return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED);
        }

        boolean blocksRemoved = false;
        try {
            contraption.removeBlocksFromWorld(level, BlockPos.ZERO);
            blocksRemoved = true;
            if (!level.addFreshEntity(entity)) {
                restoreWithCreate(level, contraption, discovery, controller.getBlockPos());
                return new Result(null, RadarAssemblyFinder.Failure.ENTITY_REJECTED);
            }
            return new Result(entity, RadarAssemblyFinder.Failure.NONE);
        } catch (Exception exception) {
            if (blocksRemoved) {
                try {
                    restoreWithCreate(level, contraption, discovery, controller.getBlockPos());
                } catch (Exception restoreException) {
                    exception.addSuppressed(restoreException);
                    restoreMissingApertureBlocks(level, discovery.sourceStates());
                }
            } else {
                // Если Create оборвался посреди removeBlocksFromWorld, вернуть только исчезнувшие
                // исходные панели; существующие блоки никогда не затираются и предметы не дропаются.
                restoreMissingApertureBlocks(level, discovery.sourceStates());
            }
            RadarDiagnostics.logException("contraption_capture_failed", controller.networkId(),
                    controller.endpoint(), null, controller.currentTickForDiagnostics(),
                    "aperture=" + discovery.apertureKind().serializedName()
                            + " count=" + discovery.apertureCount() + " rolledBack=true", exception);
            return new Result(null, RadarAssemblyFinder.Failure.CREATE_REJECTED);
        }
    }

    private static void restoreWithCreate(ServerLevel level, RadarPanelContraption contraption,
                                          RadarAssemblyFinder.Discovery discovery, BlockPos controllerPos) {
        // StructureBlockInfo уже сохранён в локальных координатах contraption. При откате
        // Create должен применить тот же мировой anchor, который использовался при захвате.
        contraption.addBlocksToWorld(level, new StructureTransform(controllerPos.above(), 0, 0, 0));
        contraption.refreshRestoredAperture(level, controllerPos);
        // Create выбрасывает entity при disassemble; если он уже был принят, безопасно убрать
        // оставшийся объект после восстановления по штатному transform.
        if (contraption.entity != null && contraption.entity.isAlive()) contraption.entity.discard();
        restoreMissingApertureBlocks(level, discovery.sourceStates());
    }

    private static void restoreMissingApertureBlocks(ServerLevel level, Map<BlockPos, BlockState> sourceStates) {
        for (Map.Entry<BlockPos, BlockState> entry : sourceStates.entrySet()) {
            BlockPos pos = entry.getKey();
            if (!level.hasChunkAt(pos)) continue;
            BlockState current = level.getBlockState(pos);
            if (current.is(Blocks.AIR)) {
                level.setBlock(pos, entry.getValue(), Block.UPDATE_CLIENTS | Block.UPDATE_NEIGHBORS
                        | Block.UPDATE_SUPPRESS_DROPS);
            }
            OverviewModuleBlock.reconcileAround(level, pos);
        }
    }

    public record Result(ControlledContraptionEntity entity,
                         RadarAssemblyFinder.Failure failure) {
        public boolean success() {
            return entity != null && failure == RadarAssemblyFinder.Failure.NONE;
        }
    }
}
