package com.electroradar.cannon.controller;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import rbasamoyai.createbigcannons.cannon_control.cannon_mount.CannonMountBlockEntity;

import com.electroradar.radar.network.NetworkId;

/** Владение автоматическим запуском: соседний сетевой контроллер наведения или перехвата направлен на эту установку. */
public final class CbcDirectControl {
    private CbcDirectControl() {}

    public static boolean owns(CannonMountBlockEntity mount) {
        if (!(mount.getLevel() instanceof ServerLevel level)) return false;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            BlockPos pos = mount.getBlockPos().relative(direction);
            if (!level.isLoaded(pos)) continue;
            BlockEntity controller = level.getBlockEntity(pos);
            NetworkId network = controller instanceof TargetControllerBlockEntity target ? target.networkIdForLinking()
                    : controller instanceof InterceptControllerBlockEntity intercept ? intercept.networkIdForLinking() : null;
            // У обоих контроллеров FACING — общее горизонтальное свойство.
            if (network != null
                    && controller.getBlockState().getValue(TargetControllerBlock.FACING) == direction.getOpposite()) {
                return true;
            }
        }
        return false;
    }
}
