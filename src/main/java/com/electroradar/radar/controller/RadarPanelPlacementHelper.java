package com.electroradar.radar.controller;

import java.util.List;
import java.util.function.Predicate;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** Наращивает апертуру по ближайшему свободному краю в её вертикальной плоскости. */
final class RadarPanelPlacementHelper implements IPlacementHelper {
    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return stack -> stack.is(RadarControllerContent.RADAR_PANEL_ITEM.get());
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return RadarPanelBlock::isPanel;
    }

    @Override
    public PlacementOffset getOffset(Player player, Level level, BlockState state,
                                     BlockPos pos, BlockHitResult hitResult) {
        Direction facing = state.getValue(RadarPanelBlock.FACING);
        List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(
                pos, hitResult.getLocation(), facing.getAxis(), direction -> {
                    BlockPos target = pos.relative(direction);
                    return !level.isOutsideBuildHeight(target) && level.hasChunkAt(target)
                            && level.getBlockState(target).canBeReplaced();
                });
        if (directions.isEmpty()) return PlacementOffset.fail();
        return PlacementOffset.success(pos.relative(directions.getFirst()), newState -> newState
                .setValue(RadarPanelBlock.FACING, facing)
                .setValue(RadarPanelBlock.PROFILE, RadarPanelBlock.PanelProfile.SURFACE));
    }
}
