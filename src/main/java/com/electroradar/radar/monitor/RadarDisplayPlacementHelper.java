package com.electroradar.radar.monitor;

import java.util.List;
import java.util.Optional;
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

/** Create placement helper, сохраняющий направление панели при наращивании существующего экрана. */
public final class RadarDisplayPlacementHelper implements IPlacementHelper {
    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return stack -> stack.is(RadarDisplayContent.RADAR_DISPLAY_ITEM.get());
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return state -> state.is(RadarDisplayContent.RADAR_DISPLAY_BLOCK.get())
                && state.hasProperty(RadarDisplayBlock.FACING);
    }

    @Override
    public PlacementOffset getOffset(Player player, Level level, BlockState state,
                                     BlockPos pos, BlockHitResult hitResult) {
        Optional<BlockPos> target = findTargetPos(level, state, pos, hitResult);
        if (target.isEmpty()) return PlacementOffset.fail();
        Direction facing = state.getValue(RadarDisplayBlock.FACING);
        return PlacementOffset.success(target.get(), newState -> newState
                .setValue(RadarDisplayBlock.FACING, facing));
    }

    /** Ищет ближайшую свободную соседнюю панель в той же вертикальной плоскости. */
    public Optional<BlockPos> findTargetPos(Level level, BlockState state, BlockPos pos,
                                            BlockHitResult hitResult) {
        Direction facing = state.getValue(RadarDisplayBlock.FACING);
        List<Direction> directions = IPlacementHelper.orderedByDistanceExceptAxis(
                pos, hitResult.getLocation(), facing.getAxis(),
                direction -> level.getBlockState(pos.relative(direction)).canBeReplaced());
        return directions.isEmpty() ? Optional.empty() : Optional.of(pos.relative(directions.getFirst()));
    }
}
