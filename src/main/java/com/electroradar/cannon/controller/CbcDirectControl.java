package com.electroradar.cannon.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

/** Владение автоматическим запуском: соседний сетевой контроллер направлен на эту установку. */
public final class CbcDirectControl {
    private CbcDirectControl() {}

    public static boolean owns(CannonMountBlockEntity mount) {
        if (!(mount.getLevel() instanceof ServerLevel level)) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = mount.getBlockPos().relative(direction);
            if (!level.isLoaded(pos)) continue;
            if (level.getBlockEntity(pos) instanceof TargetControllerBlockEntity controller
                    && controller.networkIdForLinking() != null
                    && controller.getBlockState().getValue(TargetControllerBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }
}
