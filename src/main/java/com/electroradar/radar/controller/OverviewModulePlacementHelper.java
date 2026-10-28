package com.electroradar.radar.controller;

import java.util.function.Predicate;

import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

import com.electroradar.config.RadarServerConfig;

/** ПКМ по модулю наращивает только его непрерывный столбик вверх. */
final class OverviewModulePlacementHelper implements IPlacementHelper {
    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return stack -> stack.is(RadarControllerContent.OVERVIEW_MODULE_ITEM.get());
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return OverviewModuleBlock::isOverviewModule;
    }

    @Override
    public PlacementOffset getOffset(Player player, Level level, BlockState state,
                                     BlockPos pos, BlockHitResult hitResult) {
        if (!level.hasChunkAt(pos)) return PlacementOffset.fail();
        int limit = RadarServerConfig.snapshot().overview().maxModules();
        BlockPos bottom = bottomModule(level, pos, limit);
        if (bottom == null) return PlacementOffset.fail();

        int count = 0;
        for (int offset = 0; offset <= limit; offset++) {
            BlockPos candidate = bottom.above(offset);
            if (level.isOutsideBuildHeight(candidate)) break;
            if (!level.hasChunkAt(candidate)) return PlacementOffset.fail();
            if (!OverviewModuleBlock.isOverviewModule(level.getBlockState(candidate))) break;
            count++;
            if (count > limit) return PlacementOffset.fail();
        }
        if (count <= 0 || count >= limit) return PlacementOffset.fail();

        BlockPos target = bottom.above(count);
        if (level.isOutsideBuildHeight(target) || !level.hasChunkAt(target)
                || !level.getBlockState(target).canBeReplaced()) {
            return PlacementOffset.fail();
        }
        return PlacementOffset.success(target, placed -> placed);
    }

    /** Ищет низ ограниченное число шагов; неизвестная граница чанка означает отказ. */
    private static BlockPos bottomModule(Level level, BlockPos start, int limit) {
        BlockPos bottom = start;
        for (int depth = 0; depth < limit; depth++) {
            BlockPos below = bottom.below();
            if (level.isOutsideBuildHeight(below)) return bottom;
            if (!level.hasChunkAt(below)) return null;
            if (!OverviewModuleBlock.isOverviewModule(level.getBlockState(below))) return bottom;
            bottom = below;
        }
        BlockPos below = bottom.below();
        if (level.isOutsideBuildHeight(below)) return bottom;
        if (!level.hasChunkAt(below)) return null;
        return OverviewModuleBlock.isOverviewModule(level.getBlockState(below)) ? null : bottom;
    }
}
