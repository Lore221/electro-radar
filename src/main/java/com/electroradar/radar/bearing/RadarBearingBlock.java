package com.electroradar.radar.bearing;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

public final class RadarBearingBlock extends BaseEntityBlock {
    public RadarBearingBlock(Properties properties) { super(properties); }
    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return simpleCodec(RadarBearingBlock::new); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.getBlockEntity(pos) instanceof RadarBearingBlockEntity bearing) {
            if (!level.isClientSide) bearing.toggleAssembly(player);
            return InteractionResult.sidedSuccess(level.isClientSide);
        }
        return InteractionResult.PASS;
    }
    @Override protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moving) {
        if (!state.is(replacement.getBlock()) && level.getBlockEntity(pos) instanceof RadarBearingBlockEntity bearing) {
            bearing.prepareForBlockRemoval();
        }
        super.onRemove(state, level, pos, replacement, moving);
    }
    @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new RadarBearingBlockEntity(pos, state); }
    @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state, BlockEntityType<T> type) {
        return !level.isClientSide && type == RadarBearingContent.BEARING_ENTITY.get()
                ? (world, pos, block, entity) -> ((RadarBearingBlockEntity) entity).serverTick() : null;
    }
}
